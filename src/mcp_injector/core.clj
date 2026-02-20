(ns mcp-injector.core
  "Main HTTP server and entry point for mcp-injector.
   Acts as shim between OpenClaw and LLM with optional MCP tool execution."
  (:require [org.httpkit.server :as http]
            [org.httpkit.client :as http-client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [mcp-injector.config :as config]
            [mcp-injector.openai-compat :as openai]
            [mcp-injector.mcp-client :as mcp]))

(def ^:private server-state (atom nil))

(def ^:private LLM-TIMEOUT-MS 60000)

;; Usage stats
(def ^:private usage-stats (atom {}))

(defn- is-context-overflow-error?
  "Detect context overflow errors from upstream providers through Bifrost.
   Covers JavaScript null errors and standard context overflow messages.
   Returns true if error should trigger OpenClaw's compaction/retry."
  [error-str]
  (when (string? error-str)
    (let [patterns [#"(?i)cannot read propert(?:y|ies) of undefined.*prompt"
                    #"(?i)cannot read propert(?:y|ies) of null.*prompt"
                    #"(?i)prompt_tokens.*undefined"
                    #"(?i)prompt_tokens.*null"
                    #"(?i)context window.*exceeded"
                    #"(?i)context length.*exceeded"
                    #"(?i)maximum context.*exceeded"
                    #"(?i)request.*too large"
                    #"(?i)prompt is too long"
                    #"(?i)exceeds model context"
                    #"(?i)413.*too large"
                    #"(?i)request size exceeds"]]
      (some #(re-find % error-str) patterns))))

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(defn- translate-error-for-openclaw
  "Translate upstream errors into OpenClaw-recognizable format.
   Returns map with :message, :status, and :type."
  [error-data status-code]
  (let [error-str (or (get-in error-data [:error :message])
                      (:message error-data)
                      (:details error-data)
                      (str error-data))]
    (if (is-context-overflow-error? error-str)
      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
       :status 503
       :type "context_overflow"}
      {:message (or (:message error-data) "Upstream error")
       :status status-code
       :type "upstream_error"})))

(def ^:dynamic *log-level* "info")

(def ^:private log-levels {"debug" 0 "info" 1 "warn" 2 "error" 3})

(defn- scrub-sensitive
  "Deeply redact common sensitive keys in maps"
  [data]
  (walk/postwalk
   (fn [x]
     (if (and (map-entry? x)
              (contains? #{:api_key :api-key :token :password :secret :auth}
                         (keyword (str/lower-case (name (key x))))))
       [(key x) "[REDACTED]"]
       x))
   data))

(defn- truncate-data
  "Recursively truncate large strings in data structure"
  [data limit]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (if (> (count x) limit)
         (str (subs x 0 limit) "... (truncated)")
         x)
       x))
   data))

(defn- log-request
  "Log structured request info if level >= configured log level.
   Truncates data for INFO level to keep logs readable."
  [level msg data]
  (let [config-level (get log-levels *log-level* 1)
        msg-level (get log-levels level 1)]
    (when (>= msg-level config-level)
      (let [clean-data (cond-> (scrub-sensitive data)
                         (= level "info") (truncate-data 256))
            log-entry {:timestamp (str (java.time.Instant/now))
                       :level level
                       :message msg
                       :data clean-data}]
        (println (json/generate-string log-entry))))))

(defn- extract-discovered-tools
  "Scan messages for tool schemas returned by get_tool_schema"
  [messages]
  (let [tools (->> messages
                   (filter #(= "tool" (:role %)))
                   (map #(try (json/parse-string (body->string (:content %)) true) (catch Exception _ nil)))
                   (filter #(:name %))
                   (map (fn [schema]
                          {:type "function"
                           :function (if (:function schema)
                                       (:function schema)
                                       {:name (:name schema)
                                        :description (:description schema)
                                        :parameters (:inputSchema schema)})})))
        _ (when (seq tools) (log-request "info" "Discovered tools extracted" {:count (count tools) :names (map #(get-in % [:function :name]) tools)}))]
    tools))

(defn- prepare-llm-request
  "Transform OpenClaw request for LLM compatibility.
   - Strips stream=true flag (LLM needs stream=false)
   - Removes stream_options (only valid when stream=true)
   - Injects fallbacks array for automatic provider failover
   - Injects meta-tools and discovered tools"
  [openai-request fallbacks]
  (let [meta-tools (config/get-meta-tool-definitions)
        discovered-tools (extract-discovered-tools (:messages openai-request))
        existing-tools (:tools openai-request)
        merged-tools (concat (or existing-tools [])
                             meta-tools
                             discovered-tools)]
    (-> openai-request
        (assoc :stream false)
        (dissoc :stream_options)
        (assoc :fallbacks fallbacks)
        (assoc :tools (vec (distinct merged-tools))))))

(defn- is-mcp-tool?
  "Check if a tool name is an MCP tool (format: mcp__[server]__[tool]) or a meta-tool"
  [tool-name]
  (when (string? tool-name)
    (cond
      (= tool-name "get_tool_schema") true
      (str/starts-with? tool-name "mcp__") true
      :else false)))

(defn- execute-mcp-tool
  "Execute an MCP tool call or meta-tool"
  [tool-call mcp-servers discovered-this-loop]
  (let [full-name (get-in tool-call [:function :name])
        args (json/parse-string (get-in tool-call [:function :arguments]) true)
        _ (log-request "info" "Executing MCP tool" {:tool full-name :arguments args})]
    (cond
      (= full-name "get_tool_schema")
      ;; Handle meta-tool: get_tool_schema
      (let [{:keys [server tool]} args
            server-url (or (get-in mcp-servers [:servers (keyword server) :url])
                           (get-in mcp-servers [:servers (keyword server) :uri]))]
        (if server-url
          (let [schema (mcp/get-tool-schema server-url tool)]
            (if (:error schema)
              (do (log-request "warn" "Discovery failed" {:tool full-name :error schema})
                  schema)
              (let [final-schema (assoc schema :name (str "mcp__" server "__" tool))]
                (log-request "info" "Discovery successful" {:tool (:name final-schema)})
                final-schema)))
          (let [err {:error (str "MCP server not found: " server)}]
            (log-request "error" "Server not found" {:server server})
            err)))

      ;; Handle namespaced MCP tool call
      (str/starts-with? full-name "mcp__")
      (if (contains? @discovered-this-loop full-name)
        (let [[_ server-name tool-name] (str/split full-name #"__" 3)
              server-url (or (get-in mcp-servers [:servers (keyword server-name) :url])
                             (get-in mcp-servers [:servers (keyword server-name) :uri]))]
          (if server-url
            (let [result (mcp/call-tool server-url tool-name args)]
              (log-request "info" "Tool execution complete" {:tool full-name :success (not (:error result))})
              result)
            (let [err {:error (str "MCP server not found: " server-name)}]
              (log-request "error" "Server not found" {:server server-name})
              err)))
        ;; Hallucination Trap
        (let [err {:error (str "Protocol Violation: Parameters for '" full-name "' are unknown. You MUST call 'get_tool_schema' first to discover them.")}]
          (log-request "warn" "Protocol Violation" {:tool full-name})
          err))

      :else
      (let [err {:error (str "Unknown tool: " full-name)}]
        (log-request "error" "Unknown tool type" {:tool full-name})
        err))))

(defn- build-tool-result-message
  "Build a tool result message for the conversation"
  [tool-call result]
  {:role "tool"
   :tool_call_id (:id tool-call)
   :content (json/generate-string result)})

(defn- call-llm
  "Call LLM gateway with prepared request.
   Returns {:success true :data response} or {:success false :status code :error error-data}"
  [llm-url request-body]
  (let [url (str llm-url "/v1/chat/completions")
        request-json (json/generate-string request-body)]
    (log-request "debug" "Sending request to LLM"
                 {:url url
                  :model (:model request-body)
                  :payload request-body})
    (try
      (let [response @(http-client/post (str llm-url "/v1/chat/completions")
                                        {:body request-json
                                         :headers {"Content-Type" "application/json"}
                                         :timeout LLM-TIMEOUT-MS})]
        (log-request "debug" "Received raw response from LLM"
                     {:status (:status response)
                      :body (body->string (:body response))})
        (cond
        ;; Success
          (= 200 (:status response))
          (let [response-body (:body response)
                parsed-body (json/parse-string response-body true)
                extra-fields (:extra_fields parsed-body)
                provider (:provider extra-fields)
                model-requested (:model_requested extra-fields)
                actual-model (or model-requested (:model parsed-body) (:model request-body))
                usage (:usage parsed-body)]
            ;; Track usage stats
            (when (and actual-model usage)
              (let [total (or (:total_tokens usage)
                              (+ (or (:prompt_tokens usage) 0)
                                 (or (:completion_tokens usage) 0)))
                    input (or (:prompt_tokens usage) 0)
                    output (or (:completion_tokens usage) 0)]
                (swap! usage-stats update actual-model (fn [existing]
                                                         (let [base {:model actual-model
                                                                     :requests 0
                                                                     :total-input-tokens 0
                                                                     :total-output-tokens 0
                                                                     :total-tokens 0
                                                                     :errors 0
                                                                     :rate-limits 0
                                                                     :last-updated (System/currentTimeMillis)}]
                                                           (merge base existing
                                                                  {:requests (inc (or (:requests existing) 0))
                                                                   :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
                                                                   :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
                                                                   :total-tokens (+ total (or (:total-tokens existing) 0))
                                                                   :last-updated (System/currentTimeMillis)}))))))
            (log-request "debug" "LLM returned 200"
                         {:url llm-url
                          :provider provider
                          :model_requested model-requested
                          :model (:model parsed-body)
                          :has_content (boolean (:content (get-in parsed-body [:choices 0 :message])))
                          :finish_reason (get-in parsed-body [:choices 0 :finish_reason])})
            {:success true
             :data parsed-body})

        ;; Rate limited
          (= 429 (:status response))
          (let [response-body (:body response)
                parsed-body (json/parse-string response-body true)
                extra-fields (:extra_fields parsed-body)
                provider (:provider extra-fields)
                model-requested (:model_requested extra-fields)
                actual-model (or model-requested (:model request-body))]
            ;; Track rate limit
            (swap! usage-stats update actual-model (fn [existing]
                                                     (let [base {:model actual-model
                                                                 :requests 0
                                                                 :total-input-tokens 0
                                                                 :total-output-tokens 0
                                                                 :total-tokens 0
                                                                 :errors 0
                                                                 :rate-limits 0
                                                                 :last-updated (System/currentTimeMillis)}]
                                                       (merge base existing
                                                              {:rate-limits (inc (or (:rate-limits existing) 0))
                                                               :last-updated (System/currentTimeMillis)}))))
            (log-request "warn" "Rate limited by LLM"
                         {:status 429
                          :url llm-url
                          :provider provider
                          :model_requested model-requested})
            {:success false
             :status 429
             :error {:message "Rate limit exceeded"
                     :type "rate_limit_exceeded"
                     :provider provider
                     :model model-requested
                     :details parsed-body}})

        ;; Server errors
          (>= (:status response) 500)
          (let [response-body (:body response)
                parsed-body (try
                              (json/parse-string response-body true)
                              (catch Exception e
                                {:raw_body response-body
                                 :parse_error (.getMessage e)}))
                ;; Extract Bifrost extra_fields for better error context
                extra-fields (:extra_fields parsed-body)
                provider (:provider extra-fields)
                model-requested (:model_requested extra-fields)
                raw-response (:raw_response extra-fields)
                ;; Get error from upstream if available, else use Bifrost error
                upstream-error (get-in raw-response [:error])
                error-details (or upstream-error
                                  (get parsed-body :error parsed-body))
                error-msg (or (:message error-details)
                              (:raw_body parsed-body)
                              (str parsed-body))
                translated (translate-error-for-openclaw
                            {:message error-msg :details error-details}
                            502)]
            (log-request "error" "LLM server error"
                         {:status (:status response)
                          :url llm-url
                          :provider provider
                          :model_requested model-requested
                          :error parsed-body
                          :upstream_error upstream-error
                          :translated (:message translated)
                          :is_context_overflow (= "context_overflow" (:type translated))})
            {:success false
             :status (:status translated)
             :error {:message (:message translated)
                     :type (:type translated)
                     :original_status (:status response)
                     :provider provider
                     :model model-requested
                     :details parsed-body}})

        ;; Other errors
          :else
          (do
            (log-request "warn" "LLM returned error"
                         {:status (:status response) :url llm-url})
            ;; Track error
            (swap! usage-stats update (:model request-body) (fn [existing]
                                                              (let [base {:model (:model request-body)
                                                                          :requests 0
                                                                          :total-input-tokens 0
                                                                          :total-output-tokens 0
                                                                          :total-tokens 0
                                                                          :errors 0
                                                                          :rate-limits 0
                                                                          :last-updated (System/currentTimeMillis)}]
                                                                (merge base existing
                                                                       {:errors (inc (or (:errors existing) 0))
                                                                        :last-updated (System/currentTimeMillis)}))))
            {:success false
             :status 502
             :error {:message (str "LLM error: " (:status response))
                     :type "upstream_error"
                     :status (:status response)}})))

      (catch java.util.concurrent.TimeoutException _
        (log-request "error" "LLM request timed out"
                     {:url llm-url :timeout-ms LLM-TIMEOUT-MS})
        {:success false
         :status 504
         :error {:message "LLM timeout"
                 :type "timeout"
                 :details "Request to upstream LLM gateway timed out"}})

      (catch Exception e
        (let [error-msg (.getMessage e)
              translated (translate-error-for-openclaw
                          {:message error-msg}
                          503)]
          (log-request "error" "Failed to connect to LLM"
                       {:url llm-url
                        :error error-msg
                        :translated (:message translated)
                        :is_context_overflow (= "context_overflow" (:type translated))})
          {:success false
           :status (:status translated)
           :error {:message (:message translated)
                   :type (:type translated)
                   :original_error error-msg}})))))

(defn- process-tool-calls
  "Process tool calls from LLM response.
   Executes MCP tools, passes through non-MCP tools.
   Returns map with :content and :tool-calls (for pass-through)"
  ([message mcp-servers messages active-model fallbacks llm-url]
   (process-tool-calls message mcp-servers messages active-model fallbacks llm-url 0 (atom #{})))
  ([message mcp-servers messages active-model fallbacks llm-url iteration discovered-this-loop]
   (let [tool-calls (:tool_calls message)
         content (:content message)]

     (if (or (empty? tool-calls) (>= iteration 10))
       ;; No more tool calls or max iterations reached
       (do
         (when (>= iteration 10)
           (log-request "warn" "Agent loop reached max iterations" {:iteration iteration}))
         {:content content
          :tool-calls nil})

       ;; Has tool calls - separate MCP and non-MCP
       (let [mcp-calls (filter #(is-mcp-tool? (get-in % [:function :name])) tool-calls)
             non-mcp-calls (remove #(is-mcp-tool? (get-in % [:function :name])) tool-calls)]

         (when (seq tool-calls)
           (log-request "info" "Processing tool calls"
                        {:total (count tool-calls)
                         :mcp (count mcp-calls)
                         :pass-through (count non-mcp-calls)
                         :iteration iteration}))

         (if (empty? mcp-calls)
           ;; All non-MCP - pass through
           {:content content
            :tool-calls non-mcp-calls}

           ;; Has MCP tools - execute them and continue loop
           (let [;; Execute MCP tools
                 mcp-results (map #(execute-mcp-tool % mcp-servers discovered-this-loop) mcp-calls)
                 mcp-tool-messages (map build-tool-result-message mcp-calls mcp-results)

                 ;; Update discovery set if any schemas were returned
                 _ (doseq [res mcp-results]
                     (when (and (map? res) (:name res))
                       (swap! discovered-this-loop conj (:name res))))

                 ;; Build assistant message
                 assistant-msg (assoc message :role "assistant")

                 ;; Combine messages
                 all-messages (concat messages
                                      [assistant-msg]
                                      mcp-tool-messages)

                 ;; Call LLM again
                 follow-up-req (prepare-llm-request
                                {:model active-model
                                 :messages (vec all-messages)}
                                fallbacks)
                 follow-up-result (call-llm llm-url follow-up-req)]

             (if (:success follow-up-result)
               (let [next-message (get-in (:data follow-up-result) [:choices 0 :message])]
                 (recur next-message mcp-servers all-messages active-model fallbacks llm-url (inc iteration) discovered-this-loop))
               ;; Error in follow-up call
               {:error (:error follow-up-result)
                :status (:status follow-up-result 500)}))))))))

(declare get-available-providers set-cooldown!)

(defn- try-virtual-model-chain
  "Try virtual model chain with fallback and cooldown logic
   Returns {:success true :data response} or {:success false :status code :error error-data}"
  [virtual-config chat-req llm-url]
  (let [chain (:chain virtual-config)
        cooldown-mins (get virtual-config :cooldown-minutes 5)
         ;; Don't retry on 503 (context overflow) - same model = same context limit
         ;; Let OpenClaw handle compression instead of wasting quota on same-sized models
        retry-on (get virtual-config :retry-on [429 500])
        available-providers (get-available-providers chain)]

    (log-request "debug" "Trying virtual model chain"
                 {:chain chain
                  :available available-providers
                  :cooldown-mins cooldown-mins
                  :retry-on retry-on})

    (loop [providers available-providers
           last-error nil]
      (if (empty? providers)
        ;; All providers exhausted
        (let [translated (translate-error-for-openclaw last-error 502)]
          (log-request "error" "All providers in virtual chain failed"
                       {:chain chain
                        :last-error last-error
                        :translated (:message translated)
                        :is_context_overflow (= "context_overflow" (:type translated))})
          {:success false
           :status (:status translated)
           :error {:message (:message translated)
                   :type (:type translated)
                   :original_type "all_providers_failed"
                   :details last-error}})

        ;; Try next provider
        (let [provider (first providers)
              req-with-provider (assoc chat-req :model provider)
              result (call-llm llm-url req-with-provider)]

          (cond
            ;; Success!
            (:success result)
            (do
              (log-request "info" "Virtual model succeeded"
                           {:provider provider
                            :remaining (count (rest providers))})
              (assoc result :provider provider))

            ;; Retryable error - set cooldown and try next
            (and (:status result) (some #(= % (:status result)) retry-on))
            (do
              (log-request "warn" "Provider failed with retryable error, setting cooldown"
                           {:provider provider
                            :status (:status result)
                            :cooldown-mins cooldown-mins})
              (set-cooldown! provider cooldown-mins)
              (recur (rest providers) (:error result)))

            ;; Non-retryable error - fail immediately
            :else
            (do
              (log-request "error" "Provider failed with non-retryable error"
                           {:provider provider
                            :status (:status result)})
              result)))))))

(defn- handle-chat-completion
  "Handle chat completion request from OpenClaw
   
   Flow:
   1. Receive request (stream=true from OpenClaw, stream=false for compat)
   2. Check if virtual model - if yes, use chain with fallback/cooldown
   3. If not virtual, pass through directly to LLM
   4. Check response for tool_calls
   5. Execute MCP tools, pass through non-MCP tools
   6. If MCP tools executed, call LLM again with results
   7. Return SSE for stream=true, JSON for stream=false"
  [request config mcp-servers]
  (try
    (let [body-str (slurp (:body request))
          chat-req (json/parse-string body-str true)
          model (:model chat-req)
          original-messages (:messages chat-req)
          _ (log-request "info" "Received request from OpenClaw"
                         {:model model
                          :message-count (count original-messages)})
          ;; Inject tools directory BEFORE the first call
          messages (if (seq (:servers mcp-servers))
                     (do
                       (log-request "info" "Injecting MCP tools directory"
                                    {:servers (keys (:servers mcp-servers))})
                       (let [discovered (reduce
                                          (fn [acc [server-name server-config]]
                                            (let [url (or (:url server-config) (:uri server-config))
                                                  tool-names (:tools server-config)]
                                              (if url
                                                (try
                                                  (assoc acc server-name (mcp/discover-tools url tool-names))
                                                  (catch Exception e
                                                    (log-request "warn" "Failed to discover tools"
                                                                 {:server (name server-name) :error (.getMessage e)})
                                                    acc))
                                                acc)))
                                          {}
                                          (:servers mcp-servers))]
                         (config/inject-tools-into-messages original-messages mcp-servers discovered)))
                     original-messages)
          stream-mode (:stream chat-req)
          virtual-models (config/get-virtual-models mcp-servers)
          virtual-config (or (get virtual-models model)
                             (get virtual-models (keyword model)))
          llm-url (:llm-url config)
          _ (log-request "info" "Checking for virtual model"
                         {:requested-model model
                          :is-virtual (boolean virtual-config)})
          fallbacks (config/get-llm-fallbacks mcp-servers)
          prepared-req (prepare-llm-request (assoc chat-req :messages messages) fallbacks)
          llm-result (if virtual-config
                       (try-virtual-model-chain virtual-config
                                                prepared-req
                                                llm-url)
                       (call-llm llm-url prepared-req))]
      (if (:success llm-result)
        (let [llm-response (:data llm-result)
              active-model (or (:provider llm-result) model)
              message (get-in llm-response [:choices 0 :message])
              result (process-tool-calls message mcp-servers messages
                                         active-model fallbacks llm-url)]
          (if (:error result)
            (let [error-data (:error result)
                  status (:status result 500)]
              (if stream-mode
                {:status status
                 :headers {"Content-Type" "text/event-stream"
                           "Cache-Control" "no-cache"
                           "Connection" "keep-alive"}
                 :body (str "data: " (json/generate-string {:error error-data}) "\n\ndata: [DONE]\n\n")}
                {:status status
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string {:error error-data})}))
            (let [final-content (:content result)
                  pass-through-tools (:tool-calls result)
                  final-usage (or (:usage llm-response)
                                  {:prompt_tokens 0
                                   :completion_tokens 0
                                   :total_tokens 0})]
              (if stream-mode
                (openai/send-sse-response
                 {:content final-content
                  :model model
                  :tool-calls pass-through-tools
                  :usage final-usage})
                {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string
                        (openai/build-chat-response
                         {:content final-content
                          :model model
                          :tool-calls pass-through-tools
                          :usage final-usage}))}))))
        (let [error-data (:error llm-result)
              status (:status llm-result 500)]
          (if stream-mode
            {:status status
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache"
                       "Connection" "keep-alive"}
             :body (str "data: " (json/generate-string {:error error-data}) "\n\ndata: [DONE]\n\n")}
            {:status status
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:error error-data})}))))
    (catch Exception e
      (println "Error handling request:" (.getMessage e))
      (println "Stack trace:")
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error {:message (.getMessage e)
                                            :type "internal_error"}})})))

(defn- handler
  "Main HTTP request handler"
  [request config mcp-servers]
  (case (:uri request)
    "/v1/chat/completions"
    (if (= :post (:request-method request))
      (handle-chat-completion request config mcp-servers)
      {:status 405 :body "Method not allowed"})

    "/health"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:status "ok"})}

    "/stats"
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:stats @usage-stats})}

    {:status 404 :body "Not found"}))

(defn start-server
  "Start the mcp-injector HTTP server"
  [{:keys [port host llm-url mcp-config mcp-servers virtual-models log-level]}]
  (let [mcp-servers (or mcp-servers (config/load-mcp-servers mcp-config))
        mcp-servers-with-virtual (if virtual-models
                                   (assoc-in mcp-servers [:llm-gateway :virtual-models] virtual-models)
                                   mcp-servers)
        unified-config (config/get-config mcp-servers)
        final-llm-url (or llm-url (:llm-url unified-config))
        final-log-level (or log-level (:log-level unified-config))
        handler-fn (fn [request]
                     (binding [*log-level* (or final-log-level "info")]
                       (handler request {:llm-url final-llm-url :log-level final-log-level} mcp-servers-with-virtual)))
        srv (http/run-server handler-fn {:port port :host host})
        actual-port (:local-port (meta srv))]
    (reset! server-state {:server srv
                          :config {:llm-url final-llm-url :log-level final-log-level}
                          :mcp-servers mcp-servers-with-virtual})
    (println (str "mcp-injector started on http://" host ":" actual-port))
    {:port actual-port
     :stop srv}))

(defn clear-mcp-sessions! []
  (mcp/clear-sessions!)
  (mcp/clear-schema-cache!))

(defn stop-server
  "Stop the mcp-injector server"
  [{:keys [stop]}]
  (stop)
  (reset! server-state nil)
  (println "mcp-injector stopped"))

(defn -main
  "Entry point for running mcp-injector"
  [& _args]
  (let [raw-config (config/load-config)
        mcp-servers (config/load-mcp-servers (:mcp-config raw-config))
        config (config/get-config mcp-servers)]
    (println "Starting mcp-injector...")
    (println "Config:" config)
    (start-server config)
    @(promise)))

(def ^:private cooldown-state (atom {}))

(defn reset-cooldowns!
  "Reset all cooldowns (for testing)"
  []
  (reset! cooldown-state {}))

(defn clear-cooldown!
  "Clear cooldown for specific provider (for testing)"
  [provider]
  (swap! cooldown-state dissoc provider))

(defn set-cooldown!
  "Set cooldown for provider"
  [provider minutes]
  (swap! cooldown-state assoc provider
         (+ (System/currentTimeMillis) (* minutes 60 1000))))

(defn in-cooldown?
  "Check if provider is in cooldown"
  [provider]
  (when-let [cooldown-end (get @cooldown-state provider)]
    (> cooldown-end (System/currentTimeMillis))))

(defn get-available-providers
  "Get providers from chain that aren't in cooldown"
  [chain]
  (remove in-cooldown? chain))
