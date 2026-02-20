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
  (if (string? body)
    (json/parse-string body true)
    (json/parse-string (slurp body) true)))

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
        resp (http-client/post url
                               {:headers {"Content-Type" "application/json"}
                                :body (json/generate-string payload)
                                :throw false})]
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
        (let [result (mcp/get-tool-schema s-name s-config t-name)]
          (if (:error result)
            result
            (do (swap! discovered-this-loop assoc (str "mcp__" s-name "__" t-name) result)
                result)))
        {:error (str "Server not found: " s-name)}))

    (str/starts-with? full-name "mcp__")
    (if (contains? @discovered-this-loop full-name)
      (let [[_ s-name t-name] (str/split full-name #"__" 3)
            s-config (get-in mcp-servers [:servers (keyword s-name)])]
        (if s-config
          (mcp/call-tool s-name s-config t-name args)
          {:error (str "MCP server not found: " s-name)}))
      {:error (str "Protocol Violation: Call get_tool_schema first for " full-name)})

    :else {:error (str "Unknown tool: " full-name)}))

(defn- agent-loop [llm-url prepared-req mcp-servers max-iterations]
  (let [discovered-this-loop (atom {})]
    (loop [iteration 0
           current-req prepared-req
           last-resp nil]
      (let [llm-result (call-llm llm-url current-req)]
        (if-not (:success llm-result)
          (if (and last-resp (>= iteration (or max-iterations 10)))
            {:success true :data last-resp}
            llm-result)
          (let [llm-response (:data llm-result)
                message (get-in llm-response [:choices 0 :message])
                tool-calls (:tool_calls message)]
            (if (or (empty? tool-calls) (>= iteration (or max-iterations 10)))
              {:success true :data llm-response}
              (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
                                           (str/starts-with? (get-in % [:function :name]) "mcp__"))
                                      tool-calls)]
                (if (empty? mcp-calls)
                  {:success true :data llm-response}
                  (let [results (mapv (fn [tc]
                                        (let [fname (get-in tc [:function :name])
                                              args (json/parse-string (get-in tc [:function :arguments]) true)
                                              res (execute-tool fname args mcp-servers discovered-this-loop)]
                                          {:role "tool" :tool_call_id (:id tc) :name fname :content (json/generate-string res)}))
                                      mcp-calls)
                        new-tools (into (vec (config/get-meta-tool-definitions))
                                        (concat (:tools prepared-req)
                                                (map (fn [[fname tool]]
                                                       {:type "function"
                                                        :function {:name fname
                                                                   :description (:description tool)
                                                                   :parameters (:inputSchema tool)}})
                                                     @discovered-this-loop)))
                        next-req (assoc current-req
                                        :messages (vec (concat (:messages current-req) [message] results))
                                        :tools (vec new-tools))]
                    (log-request "info" "Tool Loop" {:iteration iteration :calls (count mcp-calls)})
                    (recur (inc iteration) next-req llm-response)))))))))))

(defn- prepare-llm-request [openai-request mcp-servers]
  (let [meta-tools (config/get-meta-tool-definitions)
        existing-tools (:tools openai-request)
        fallbacks (config/get-llm-fallbacks mcp-servers)
        merged-tools (concat (or existing-tools [])
                             meta-tools)]
    (-> openai-request
        (assoc :stream false)
        (dissoc :stream_options)
        (assoc :fallbacks fallbacks)
        (assoc :tools (vec (distinct merged-tools))))))

(defn set-cooldown! [provider minutes]
  (swap! cooldown-state assoc provider
         (+ (System/currentTimeMillis) (* minutes 60 1000))))

(defn in-cooldown? [provider]
  (when-let [cooldown-end (get @cooldown-state provider)]
    (> cooldown-end (System/currentTimeMillis))))

(defn reset-cooldowns! []
  (reset! cooldown-state {}))

(defn- try-virtual-model-chain
  [virtual-config prepared-req llm-url mcp-servers max-iterations]
  (let [chain (:chain virtual-config)
        cooldown-mins (get virtual-config :cooldown-minutes 5)
        retry-on (get virtual-config :retry-on [429 500])
        available (remove in-cooldown? chain)]
    (loop [providers available
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
  (let [chat-req (parse-body (:body request))
        model (:model chat-req)
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
        {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body}))))

(defn- handler [request mcp-servers config]
  (case (:uri request)
    "/v1/chat/completions"
    (if (= :post (:request-method request))
      (handle-chat-completion request mcp-servers config)
      {:status 405 :body "Method not allowed"})

    "/health"
    {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}

    "/stats"
    {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:stats @usage-stats})}

    {:status 404 :body "Not found"}))

(defn start-server [{:keys [port host mcp-config] :as config}]
  (let [mcp-servers (or (:mcp-servers config) (config/load-mcp-servers mcp-config))
        mcp-servers (cond-> mcp-servers
                      (:virtual-models config) (assoc-in [:llm-gateway :virtual-models] (:virtual-models config))
                      (:llm-url config) (assoc-in [:llm-gateway :url] (:llm-url config)))
        s (http/run-server (fn [req] (handler req mcp-servers config)) {:port (or port 0) :ip host})]
    (log-request "info" "mcp-injector started" {:port (:local-port (meta s))})
    (reset! server-state s)
    {:port (:local-port (meta s))
     :stop s}))

(defn stop-server [s]
  (when s (if (fn? s) (s :timeout 100) ((:stop s) :timeout 100)) (reset! server-state nil) (mcp/clear-tool-cache!)))

(defn clear-mcp-sessions! []
  (mcp/clear-tool-cache!))

(defn -main [& _args] (start-server (config/load-config)))
