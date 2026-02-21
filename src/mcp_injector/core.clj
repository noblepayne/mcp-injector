(ns mcp-injector.core
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [mcp-injector.config :as config]
            [mcp-injector.openai-compat :as openai]
            [mcp-injector.mcp-client :as mcp]))

(def ^:private server-state (atom nil))
(def ^:private usage-stats (atom {}))
(def ^:private cooldown-state (atom {}))

(defn- log-request [level message data]
  (println (json/generate-string
            {:timestamp (str (java.time.Instant/now))
             :level level
             :message message
             :data data})))

(defn- parse-body [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Exception e
      (throw (ex-info "Failed to parse JSON body" {:type :json-parse-error} e)))))

(defn- is-context-overflow-error? [error-str]
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

(defn- translate-error-for-openclaw [error-data status-code]
  (let [error-str (or (get-in error-data [:error :message])
                      (:message error-data)
                      (:details error-data)
                      (str error-data))]
    (cond
      (is-context-overflow-error? error-str)
      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
       :status 503
       :type "context_overflow"
       :details error-data}

      (= 429 status-code)
      {:message (or (:message error-data) "Rate limit exceeded")
       :status 429
       :type "rate_limit_exceeded"
       :details error-data}

      :else
      {:message (or (:message error-data) "Upstream error")
       :status 502
       :type "upstream_error"
       :details error-data})))

(defn- call-llm [base-url payload]
  (let [url (str (str/replace base-url #"/$" "") "/v1/chat/completions")
        resp (try
               (http-client/post url
                                 {:headers {"Content-Type" "application/json"}
                                  :body (json/generate-string payload)
                                  :throw false})
               (catch Exception e
                 {:status 502 :body (json/generate-string {:error {:message (.getMessage e)}})}))]
    (if (= 200 (:status resp))
      {:success true :data (json/parse-string (:body resp) true)}
      (let [status (:status resp)
            error-data (try (json/parse-string (:body resp) true) (catch Exception _ (:body resp)))
            translated (translate-error-for-openclaw error-data status)]
        (log-request "warn" "LLM Error" {:status status :body (:body resp) :translated translated})
        {:success false :status (:status translated) :error translated}))))

(defn- track-usage! [model usage]
  (when (and model usage)
    (swap! usage-stats update model (fn [existing]
                                      (let [input (or (:prompt_tokens usage) 0)
                                            output (or (:completion_tokens usage) 0)
                                            total (or (:total_tokens usage) (+ input output))]
                                        {:requests (inc (or (:requests existing) 0))
                                         :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
                                         :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
                                         :total-tokens (+ total (or (:total-tokens existing) 0))
                                         :last-updated (System/currentTimeMillis)})))))

(defn- execute-tool [full-name args mcp-servers discovered-this-loop]
  (cond
    (= full-name "get_tool_schema")
    (let [s-name (:server args)
          t-name (:tool args)
          s-config (get-in mcp-servers [:servers (keyword s-name)])]
      (if s-config
        (let [schema (mcp/get-tool-schema (name s-name) s-config t-name)]
          (if (:error schema)
            {:error (:error schema)}
            schema))
        {:error (str "Server not found: " s-name)}))

    (str/starts-with? full-name "mcp__")
    (let [t-name (str/replace full-name #"^mcp__" "")
          [s-name real-t-name] (if (str/includes? t-name "__")
                                 (let [idx (str/last-index-of t-name "__")]
                                   [(subs t-name 0 idx) (subs t-name (+ idx 2))])
                                 [nil t-name])
          s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
      (if (and s-name s-config)
        (mcp/call-tool (name s-name) s-config real-t-name args)
        (let [server-id (some (fn [[s-id tools]]
                                (when (some #(= real-t-name (:name %)) tools) s-id))
                              discovered-this-loop)
              s-conf (when server-id (get-in mcp-servers [:servers (keyword server-id)]))]
          (if (and server-id s-conf)
            (mcp/call-tool (name server-id) s-conf real-t-name args)
            {:error (str "Tool not found or server not configured for: " full-name)}))))

    :else
    (let [server-id (some (fn [[s-id tools]]
                            (when (some #(= full-name (:name %)) tools) s-id))
                          discovered-this-loop)
          s-conf (when server-id (get-in mcp-servers [:servers (keyword server-id)]))]
      (if (and server-id s-conf)
        (mcp/call-tool (name server-id) s-conf full-name args)
        {:error (str "Tool not found or server not configured: " full-name)}))))

(defn- agent-loop [llm-url payload mcp-servers max-iterations]
  (loop [current-payload payload
         iteration 0]
    (if (>= iteration max-iterations)
      {:success false :error "Max iterations reached"}
      (let [_ (log-request "info" "Tool Loop" {:iteration iteration :calls (count (get-in current-payload [:messages]))})
            resp (call-llm llm-url current-payload)]
        (if-not (:success resp)
          resp
          (let [choices (get-in resp [:data :choices])
                message (get-in (first choices) [:message])
                tool-calls (:tool_calls message)]
            (if-not tool-calls
              resp
              (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
                                           (str/starts-with? (get-in % [:function :name]) "mcp__"))
                                      tool-calls)]
                (if (empty? mcp-calls)
                  resp
                  (let [discovered (reduce (fn [acc [s-id s-conf]]
                                             (assoc acc (name s-id) (mcp/discover-tools (name s-id) s-conf (:tools s-conf))))
                                           {} (:servers mcp-servers))
                        results (mapv (fn [tc]
                                        (let [fn-name (get-in tc [:function :name])
                                              args-str (get-in tc [:function :arguments])
                                              args (try (json/parse-string args-str true) (catch Exception _ args-str))
                                              result (execute-tool fn-name args mcp-servers discovered)]
                                          {:role "tool"
                                           :tool_call_id (:id tc)
                                           :name fn-name
                                           :content (if (string? result) result (json/generate-string result))}))
                                      mcp-calls)
                        new-messages (conj (vec (:messages current-payload))
                                           message)
                        new-messages (into new-messages results)]
                    (recur (assoc current-payload :messages new-messages) (inc iteration))))))))))))

(defn- set-cooldown! [provider minutes]
  (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))

(defn- is-on-cooldown? [provider]
  (if-let [expiry (get @cooldown-state provider)]
    (if (> expiry (System/currentTimeMillis))
      true
      (do (swap! cooldown-state dissoc provider) false))
    false))

(defn reset-cooldowns! []
  (reset! cooldown-state {}))

(defn- prepare-llm-request [chat-req _mcp-servers]
  (-> chat-req
      (dissoc :stream)
      (update :messages (fn [msgs]
                          (mapv (fn [m]
                                  (if (and (= (:role m) "assistant") (:tool_calls m))
                                    (update m :tool_calls (fn [tcs]
                                                            (mapv #(dissoc % :index) tcs)))
                                    m))
                                msgs)))))

(defn- try-virtual-model-chain [config prepared-req llm-url mcp-servers max-iterations]
  (let [chain (:chain config)
        retry-on (set (:retry-on config [429 500]))
        cooldown-mins (get config :cooldown-minutes 5)]
    (loop [providers (filter #(not (is-on-cooldown? %)) chain)
           last-error nil]
      (if (empty? providers)
        {:success false :status 502 :error (or last-error {:message "All providers failed"})}
        (let [provider (first providers)
              req (-> prepared-req
                      (assoc :model provider)
                      (dissoc :fallbacks))
              result (agent-loop llm-url req mcp-servers max-iterations)]
          (if (:success result)
            result
            (if (some #(= % (:status result)) retry-on)
              (do (set-cooldown! provider cooldown-mins)
                  (recur (rest providers) (:error result)))
              result)))))))

(defn- handle-chat-completion [request mcp-servers config]
  (try
    (let [chat-req (parse-body (:body request))
          model (:model chat-req)
          _ (log-request "info" "Chat Completion Started" {:model model :stream (:stream chat-req)})
          discovered (reduce (fn [acc [s-name s-conf]]
                               (let [url (or (:url s-conf) (:uri s-conf))
                                     cmd (:cmd s-conf)]
                                 (if (or url cmd)
                                   (try (assoc acc s-name (mcp/discover-tools (name s-name) s-conf (:tools s-conf)))
                                        (catch Exception e
                                          (log-request "warn" "Discovery failed" {:server s-name :error (.getMessage e)})
                                          acc))
                                   acc)))
                             {} (:servers mcp-servers))
          messages (config/inject-tools-into-messages (:messages chat-req) mcp-servers discovered)
          llm-url (or (:llm-url config) (config/get-llm-url mcp-servers))
          virtual-models (config/get-virtual-models mcp-servers)
          virtual-config (or (get virtual-models model) (get virtual-models (keyword model)))
          prepared-req (prepare-llm-request (assoc chat-req :messages messages) mcp-servers)
          max-iter (or (:max-iterations config) 10)
          result (if virtual-config
                   (try-virtual-model-chain virtual-config prepared-req llm-url mcp-servers max-iter)
                   (agent-loop llm-url prepared-req mcp-servers max-iter))]
      (if (:success result)
        (let [final-resp (:data result)
              _ (track-usage! model (:usage final-resp))
              _ (log-request "info" "Chat Completion Success" {:model model :usage (:usage final-resp)})
              body (if (:stream chat-req)
                     (openai/build-chat-response-streaming
                      {:content (get-in final-resp [:choices 0 :message :content])
                       :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
                       :model model
                       :usage (:usage final-resp)})
                     (json/generate-string (assoc final-resp :model model)))]
          {:status 200 :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})
        (let [status (or (:status result) 500)
              error-data (:error result)
              error-msg (if (map? error-data) (:message error-data) (str "Failed: " error-data))
              error-type (get-in result [:error :type] "internal_error")
              body (if (:stream chat-req)
                     (str "data: " (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}) "\n\ndata: [DONE]\n\n")
                     (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
          {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
    (catch Exception e
      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
        (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e)})
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
                                              :type err-type}})}))))

(defn get-gateway-state []
  {:cooldowns @cooldown-state
   :usage @usage-stats
   :warming-up? (not (realized? (get @server-state :warmup-future)))})

(defn- handle-api [request _mcp-servers _config]
  (let [uri (:uri request)
        method (:request-method request)]
    (case [method uri]
      [:get "/api/v1/status"]
      {:status 200 :body (json/generate-string {:status "ok" :version "1.0.0" :warming-up? (:warming-up? (get-gateway-state))})}

      [:get "/api/v1/mcp/tools"]
      {:status 200 :body (json/generate-string (mcp/get-cache-state))}

      [:post "/api/v1/mcp/reset"]
      (do (mcp/clear-tool-cache!)
          {:status 200 :body (json/generate-string {:message "MCP state reset successful"})})

      [:get "/api/v1/llm/state"]
      {:status 200 :body (json/generate-string (get-gateway-state))}

      [:post "/api/v1/llm/cooldowns/reset"]
      (do (reset-cooldowns!)
          {:status 200 :body (json/generate-string {:message "Cooldowns reset successful"})})

      [:get "/api/v1/stats"]
      {:status 200 :body (json/generate-string {:stats @usage-stats})}

      {:status 404 :body (json/generate-string {:error "Not found"})})))

(defn- handler [request mcp-servers config]
  (try
    (let [uri (:uri request)]
      (cond
        (= uri "/v1/chat/completions")
        (if (= :post (:request-method request))
          (handle-chat-completion request mcp-servers config)
          {:status 405 :body "Method not allowed"})

        (= uri "/health")
        {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}

        (str/starts-with? uri "/api/v1")
        (handle-api request mcp-servers config)

        :else
        {:status 404 :body "Not found"}))
    (catch Exception e
      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
        (log-request "error" "Request failed" {:uri (:uri request) :type err-type :message (.getMessage e)})
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
                                              :type err-type}})}))))

(defn start-server [mcp-config]
  (let [initial-config (if (and (map? mcp-config) (not (:servers mcp-config)))
                         mcp-config
                         {})
        port (or (:port initial-config)
                 (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
                 8080)
        host (or (:host initial-config)
                 (System/getenv "MCP_INJECTOR_HOST")
                 "127.0.0.1")
        llm-url (or (:llm-url initial-config)
                    (System/getenv "MCP_INJECTOR_LLM_URL")
                    "http://localhost:11434")
        log-level (or (:log-level initial-config)
                      (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
        max-iterations (or (:max-iterations initial-config)
                           (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
                           10)
        mcp-config-path (or (:mcp-config-path initial-config)
                            (System/getenv "MCP_INJECTOR_MCP_CONFIG")
                            "mcp-servers.edn")
        mcp-servers (cond
                      (and (map? mcp-config) (:servers mcp-config)) mcp-config
                      (:mcp-servers initial-config) (:mcp-servers initial-config)
                      :else (config/load-mcp-servers mcp-config-path))
        final-config {:port port :host host :llm-url llm-url :log-level log-level :max-iterations max-iterations :mcp-config-path mcp-config-path}
        srv (http/run-server (fn [req] (handler req mcp-servers final-config)) {:port port :host host})
        actual-port (or (:local-port (meta srv)) port)
        warmup-fut (future (mcp/warm-up! mcp-servers))]
    (reset! server-state {:server srv :port actual-port :warmup-future warmup-fut})
    (log-request "info" "mcp-injector started" (assoc final-config :port actual-port))
    {:server srv :port actual-port :warmup-future warmup-fut}))

(defn stop-server [s]
  (when s
    (let [srv (cond (fn? s) s (map? s) (:server s) :else s)
          fut (when (map? s) (:warmup-future s))]
      (when fut (future-cancel fut))
      (when (fn? srv) (srv :timeout 100))
      (reset! server-state nil)
      (mcp/clear-tool-cache!))))

(defn clear-mcp-sessions! []
  (mcp/clear-tool-cache!))

(defn -main [& _args]
  (let [initial-config (config/load-config)
        mcp-servers (config/load-mcp-servers (:mcp-config initial-config))
        unified-config (config/get-config mcp-servers)]
    (start-server unified-config)))
