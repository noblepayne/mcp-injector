(ns mcp-injector.core
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.stacktrace]
            [mcp-injector.config :as config]
            [mcp-injector.openai-compat :as openai]
            [mcp-injector.mcp-client :as mcp]
            [mcp-injector.audit :as audit]
            [mcp-injector.pii :as pii]
            [mcp-injector.policy :as policy]
            [mcp-injector.storage :as storage]
            [mcp-injector.projection :as projection]))

(declare log-request)

(def ^:private server-state (atom nil))
(def ^:private usage-stats (atom {}))
(def ^:private pii-global-salt (atom :unset))

(defn- get-or-create-pii-salt []
  (let [current @pii-global-salt]
    (if (not= current :unset)
      current
      (swap! pii-global-salt
             (fn [v]
               (if (not= v :unset)
                 v
                 (config/resolve-secure-secret "MCP_INJECTOR_PII_SECRET" "PII salt" 16 log-request)))))))

(defn derive-pii-salt
  "Derive a stable salt for PII tokenization using a simple hash.
   Uses global secret + session identity to prevent cross-session tracking."
  [session-id]
  (let [secret (get-or-create-pii-salt)
        input (str secret "|" session-id)
        bytes (.getBytes input "UTF-8")
        digest (java.security.MessageDigest/getInstance "SHA-256")
        hash (.digest digest bytes)]
    (apply str (map #(format "%02x" %) hash))))

(def ^:private cooldown-state (atom {}))
(def ^:private ^:dynamic *request-id* nil)
(def ^:private ^:dynamic *audit-config* nil)

;; Tool categorization for Strict Priority logic
(def ^:private INTERNAL_TOOLS #{"get_tool_schema" "clojure-eval"})

(defn- internal-call?
  "Returns true if tool-name is managed by mcp-injector (internal to the kernel)."
  [tool-name]
  (or (INTERNAL_TOOLS tool-name)
      (and tool-name (str/starts-with? tool-name "mcp__"))))

(def ^:private eval-accident-tripwires
  "Catches common dangerous patterns emitted by hallucinating LLMs.
   NOT a security boundary — string-search is trivially bypassed.
   clojure-eval is RCE-by-design; only enable it for fully trusted
   models in isolated environments. See README security notice."
  ["System/exit" "java.lang.Runtime" "clojure.java.shell"
   "java.io.File/delete" "java.io.File/create" "sh " "sh\t" "sh\""
   "ProcessBuilder" "getRuntime" "(.exec" "(.. Runtime"])

(defn- log-request
  ([level message data]
   (log-request level message data nil))
  ([level message data context]
   (let [log-entry (merge {:timestamp (str (java.time.Instant/now))
                           :level level
                           :message message
                           :request-id (or *request-id* "none")}
                          context
                          {:data data})]
     (println (json/generate-string log-entry))
     (when *audit-config*
       (try
         (audit/append-event! (:secret *audit-config*) level log-entry)
         (catch Exception e
           (binding [*out* *err*]
             (println (json/generate-string
                       {:timestamp (str (java.time.Instant/now))
                        :level "error"
                        :message "AUDIT LOG WRITE FAILURE"
                        :error (.getMessage e)})))))))))

(defn- parse-body [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Exception e
      (throw (ex-info "Failed to parse JSON body"
                      {:type :json_parse_error
                       :status 400
                       :message "Failed to parse JSON body. Please ensure your request is valid JSON."} e)))))

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
      {:message "Context overflow: prompt too large for the model."
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

(defn- call-llm [base-url payload trace-ctx]
  (let [url (str (str/replace base-url #"/$" "") "/v1/chat/completions")
        start-nano (System/nanoTime)
        trace-headers (when trace-ctx
                        {"traceparent" (openai/build-traceparent (:trace-id trace-ctx) (:parent-id trace-ctx))})
        resp (try
               (http-client/post url
                                 {:headers (merge {"Content-Type" "application/json"} trace-headers)
                                  :body (json/generate-string payload)
                                  :throw false})
               (catch Exception e
                 (log-request "error" "LLM call failed" {:error (.getMessage e) :duration-ms (/ (- (System/nanoTime) start-nano) 1000000.0)} {:url url})
                 {:status 502 :body (json/generate-string {:error {:message "Upstream LLM provider error"}})}))]
    (if (= 200 (:status resp))
      {:success true :data (json/parse-string (:body resp) true)}
      (let [status (:status resp)
            error-data (try (json/parse-string (:body resp) true) (catch Exception _ (:body resp)))
            translated (translate-error-for-openclaw error-data status)
            duration (/ (- (System/nanoTime) start-nano) 1000000.0)]
        (log-request "warn" "LLM Error" {:status status :body (:body resp) :translated translated :duration-ms duration} nil)
        {:success false :status (:status translated) :error translated}))))

(defn- record-completion! [alias provider usage]
  (when usage
    (let [now (System/currentTimeMillis)
          update-entry (fn [existing usage]
                         (let [input (or (:prompt_tokens usage) 0)
                               output (or (:completion_tokens usage) 0)
                               total (or (:total_tokens usage) (+ input output))]
                           {:requests (inc (or (:requests existing) 0))
                            :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
                            :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
                            :total-tokens (+ total (or (:total-tokens existing) 0))
                            :rate-limits (or (:rate-limits existing) 0)
                            :context-overflows (or (:context-overflows existing) 0)
                            :last-updated now}))]
      (swap! usage-stats
             (fn [stats]
               (cond-> stats
                 alias (update alias update-entry usage)
                 (and provider (not= provider alias)) (update provider update-entry usage)))))))

(defn- track-provider-failure! [provider status]
  (when provider
    (let [now (System/currentTimeMillis)
          counter (if (= status 503) :context-overflows :rate-limits)]
      (swap! usage-stats update provider
             (fn [existing]
               (assoc (or existing {:requests 0
                                    :total-input-tokens 0
                                    :total-output-tokens 0
                                    :total-tokens 0})
                      counter (inc (or (get existing counter) 0))
                      :last-updated now))))))

(defn reset-usage-stats! []
  (reset! usage-stats {}))

(defn- parse-tool-name [full-name]
  (if (and (string? full-name) (str/includes? full-name "__"))
    (let [t-name (str/replace full-name #"^mcp__" "")
          idx (str/last-index-of t-name "__")]
      (if (pos? idx)
        [(subs t-name 0 idx) (subs t-name (+ idx 2))]
        [nil full-name]))
    [nil full-name]))

(defn- execute-tool [full-name args mcp-servers discovered-map governance context trace-ctx]
  (let [policy-result (policy/allow-tool? (:policy governance) full-name context)
        pii-config {:mode :replace :salt (:session-id context)}
        [redacted-args _ _] (openai/redact-for-receipt args pii-config {})]
    (if-not (:allowed? policy-result)
      (do
        (log-request "warn" "Tool Blocked by Policy" {:tool full-name :reason (:reason policy-result)} context)
        [{:error "Tool execution denied"} discovered-map {:name full-name :args redacted-args :ms 0 :status :policy-blocked :error "Tool execution denied"}])
      (cond
        (= full-name "get_tool_schema")
        (let [full-tool-name (:tool args)
              [s-name t-name] (parse-tool-name full-tool-name)
              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
          (if (and s-name s-config t-name)
            (let [schema (mcp/get-tool-schema (name s-name) s-config t-name (:policy governance))]
              (if (:error schema)
                [schema discovered-map {:name full-name :args redacted-args :ms 0 :status :error :error (:error schema)}]
                [schema (assoc discovered-map full-tool-name schema) {:name full-name :args redacted-args :ms 0 :status :ok :error nil}]))
            [{:error (str "Invalid tool name: " full-tool-name)} discovered-map {:name full-name :args redacted-args :ms 0 :status :error :error "Invalid tool name"}]))

        (= full-name "clojure-eval")
        (try
          (let [code (:code args)
                blocked? (some #(str/includes? code %) eval-accident-tripwires)
                start-nano (System/nanoTime)]
            (if blocked?
              (do
                (log-request "error" "clojure-eval tripwire triggered" {:code (subs code 0 (min 100 (count code)))} context)
                [{:error "Security Violation: prohibited system calls detected"} discovered-map {:name full-name :args redacted-args :ms 0 :status :error :error "Security Violation"}])
              (let [eval-timeout (or (:eval-timeout-ms governance) 5000)
                    eval-future (future (load-string code))
                    result (deref eval-future eval-timeout ::timeout)
                    duration (/ (- (System/nanoTime) start-nano) 1000000.0)]
                (if (= result ::timeout)
                  (do
                    (future-cancel eval-future)
                    (log-request "error" "clojure-eval timeout" {:duration-ms duration} context)
                    [{:error (format "Eval error: Evaluation timed out after %d seconds" (quot (long eval-timeout) 1000))} discovered-map {:name full-name :args redacted-args :ms duration :status :error :error "Timeout"}])
                  (do
                    (log-request "debug" "clojure-eval success" {:duration-ms duration} context)
                    [(json/generate-string result) discovered-map {:name full-name :args redacted-args :ms duration :status :ok :error nil}])))))
          (catch Exception e
            [{:error (str "Eval error: " (.getMessage e))} discovered-map {:name full-name :args redacted-args :ms 0 :status :error :error (.getMessage e)}]))

        (str/starts-with? full-name "mcp__")
        (let [[s-name real-t-name] (parse-tool-name full-name)
              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))
              start-nano (System/nanoTime)
              trace-headers (when trace-ctx
                              {"traceparent" (openai/build-traceparent (:trace-id trace-ctx) (:parent-id trace-ctx))})]
          (if (and s-name s-config)
            (let [result (mcp/call-tool (name s-name) s-config real-t-name args (:policy governance) trace-headers)
                  duration (/ (- (System/nanoTime) start-nano) 1000000.0)
                  _ (log-request "debug" "MCP Tool Call" {:tool full-name :duration-ms duration} context)]
              (if (:error result)
                [result discovered-map {:name full-name :args redacted-args :ms duration :status :error :error (:error result)}]
                (let [schema (mcp/get-tool-schema (name s-name) s-config real-t-name (:policy governance))]
                  (if (:error schema)
                    [result discovered-map {:name full-name :args redacted-args :ms duration :status :ok :error nil}]
                    [result (assoc discovered-map full-name schema) {:name full-name :args redacted-args :ms duration :status :ok :error nil}]))))
            (if-let [_ (get discovered-map full-name)]
              (let [[_ s-name-auto real-t-auto] (str/split full-name #"__" 3)
                    s-conf-auto (get-in mcp-servers [:servers (keyword s-name-auto)])
                    result (mcp/call-tool (name s-name-auto) s-conf-auto real-t-auto args (:policy governance) trace-headers)
                    duration (/ (- (System/nanoTime) start-nano) 1000000.0)
                    _ (log-request "debug" "MCP Tool Call" {:tool full-name :duration-ms duration} context)]
                [result discovered-map {:name full-name :args redacted-args :ms duration :status (if (:error result) :error :ok) :error (:error result)}])
              [{:error (str "Unknown tool: " full-name)} discovered-map {:name full-name :args redacted-args :ms 0 :status :error :error "Unknown tool"}])))

        :else [{:error (str "Unknown tool: " full-name)} discovered-map {:name full-name :args redacted-args :ms 0 :status :error :error "Unknown tool"}]))))

(defn- scrub-messages [messages vault pii-salt governance]
  (let [pii-enabled (get-in governance [:pii :enabled] true)]
    (reduce
     (fn [[msgs current-vault] m]
       (let [content (:content m)
             role (:role m)]
         (if (and (string? content)
                  (contains? #{"system" "user" "assistant"} role)
                  pii-enabled)
           (let [config {:mode :replace :salt pii-salt}
                 [redacted-content new-vault _] (pii/redact-data content config current-vault)]
             [(conj msgs (assoc m :content redacted-content)) new-vault])
           [(conj msgs m) current-vault])))
     [[] vault]
     messages)))

(defn- restore-tool-args [args vault mcp-servers full-tool-name governance]
  (let [[server tool] (parse-tool-name full-tool-name)
        trust (if server
                (config/get-server-trust mcp-servers server tool)
                (config/get-passthrough-trust governance full-tool-name))]
    (if (nil? args)
      nil
      (case trust
        :restore (pii/restore-tokens args vault)
        :block (let [args-str (if (string? args) args (json/generate-string args))
                     has-tokens (re-find #"\[[A-Z_]+_[a-f0-9]+\]" args-str)]
                 (if has-tokens
                   :pii-blocked
                   args))
        args))))

(defn- redact-tool-output [raw-output vault pii-salt governance]
  (let [pii-enabled (get-in governance [:pii :enabled] true)
        config {:mode :replace :salt pii-salt}
        parse-json (fn [s] (try (json/parse-string s true) (catch Exception _ nil)))
        parsed (parse-json raw-output)
        [redacted new-vault detected]
        (if pii-enabled
          (if parsed
            (let [parsed (cond
                           (map? parsed)
                           (if (string? (:text parsed))
                             (or (parse-json (:text parsed)) parsed)
                             parsed)
                           (sequential? parsed)
                           (mapv (fn [item]
                                   (if (and (map? item) (string? (:text item)))
                                     (assoc item :text (or (parse-json (:text item)) (:text item)))
                                     item))
                                 parsed)
                           :else parsed)
                  [redacted-struct vault-after labels] (pii/redact-data parsed config vault)]
              [(json/generate-string redacted-struct) vault-after labels])
            (let [[redacted-str vault-after labels] (pii/redact-data raw-output config vault)]
              [redacted-str vault-after labels]))
          [raw-output vault []])]
    (when (and (seq detected) pii-enabled)
      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
    [redacted new-vault]))

(defn- execute-internal-batch
  "Execute a batch of internal tool calls (mcp__*, clojure-eval, get_tool_schema).
   Returns [results new-vault new-discovered work-log]."
  [tool-calls vault-state discovered-state mcp-servers governance context trace-ctx]
  (reduce
   (fn [[results-acc vault-acc disc-acc log-acc] tc]
     (let [fn-name (get-in tc [:function :name])
           args-str (get-in tc [:function :arguments])
           parse-result (try
                          {:success true :args (json/parse-string args-str true)}
                          (catch Exception e
                            {:success false :error (.getMessage e)}))]
       (if (:success parse-result)
         (let [restored-args (restore-tool-args (:args parse-result) vault-acc mcp-servers fn-name governance)]
           (if (= restored-args :pii-blocked)
             [(conj results-acc {:role "tool"
                                 :tool_call_id (:id tc)
                                 :name fn-name
                                 :content (json/generate-string {:error "PII Blocked: tool has :trust :block and received redacted tokens"})})
              vault-acc
              disc-acc
              (conj log-acc {:name fn-name :args {} :ms 0 :status :pii-blocked :error "PII Blocked"})]
             (let [[result updated-disc entry-meta] (execute-tool fn-name restored-args mcp-servers disc-acc governance context trace-ctx)
                   raw-content (if (string? result) result (json/generate-string result))
                   [redacted updated-vault] (redact-tool-output raw-content vault-acc (:session-id context) governance)]
               [(conj results-acc {:role "tool"
                                   :tool_call_id (:id tc)
                                   :name fn-name
                                   :content redacted})
                updated-vault
                updated-disc
                (conj log-acc entry-meta)])))
         [(conj results-acc {:role "tool"
                             :tool_call_id (:id tc)
                             :name fn-name
                             :content (json/generate-string
                                       {:error "Malformed tool arguments JSON"
                                        :details {:args-str args-str
                                                  :parse-error (:error parse-result)}})})
          vault-acc
          disc-acc
          (conj log-acc {:name fn-name :args {} :ms 0 :status :error :error (str "Parse error: " (:error parse-result))})])))
   [[] vault-state discovered-state []]
   tool-calls))

(defn- prepare-passthrough-handoff
  "Prepare a passthrough tool batch for Gateway handoff.
   Restores PII based on trust rules, returns updated assistant message."
  [assistant-message tool-calls vault-state mcp-servers governance]
  (assoc assistant-message :tool_calls
         (mapv (fn [tc]
                 (let [fn-name (get-in tc [:function :name])
                       args-str (get-in tc [:function :arguments])
                       ;; Parse arguments safely
                       args (try (json/parse-string args-str true) (catch Exception _ nil))
                       restored (if args
                                  (restore-tool-args args vault-state mcp-servers fn-name governance)
                                  {:error "Malformed JSON arguments"})]
                   (if (= restored :pii-blocked)
                     (assoc-in tc [:function :arguments] (json/generate-string {:error "PII Blocked"}))
                     (assoc-in tc [:function :arguments] (json/generate-string restored)))))
               tool-calls)))

(defn- message->work-log-entry
  [message provider model turn-index]
  (let [content-blocks (cond-> []
                         (and (:content message) (not-empty (:content message)))
                         (conj {:type "text" :text (:content message)})

                         (:thinking message)
                         (conj {:type "thinking"
                                :thinking (:thinking message)
                                :signature (:signature message)})

                         (seq (:tool_calls message))
                         (into (mapv (fn [tc]
                                       {:type "tool_use"
                                        :id (:id tc)
                                        :name (get-in tc [:function :name])
                                        :input (try (json/parse-string (get-in tc [:function :arguments]) true)
                                                    (catch Exception _ {}))})
                                     (:tool_calls message))))]
    {:role "assistant"
     :content content-blocks
     :_meta {:provider provider
             :model model
             :turn-index turn-index
             :timestamp (str (java.time.Instant/now))}}))

(defn- tool-result->work-log-entry
  [tool-call-id _tool-name result provider model turn-index]
  {:role "tool"
   :content [{:type "tool_result"
              :tool_use_id tool-call-id
              :content [{:type "text" :text result}]}]
   :_meta {:provider provider
           :model model
           :turn-index turn-index
           :timestamp (str (java.time.Instant/now))}})

(defn- agent-loop [llm-url payload mcp-servers max-iterations governance pii-salt trace-ctx]
  (let [model (:model payload)
        vault {}
        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
        context {:model model :request-id request-id :session-id pii-salt}
        [init-messages init-vault] (scrub-messages (:messages payload) vault pii-salt governance)]
    (loop [current-payload (assoc payload :messages init-messages :request-id request-id)
           vault-state init-vault
           discovered-state {}
           iteration 0
           turns-acc []
           work-log []]
      (if (>= iteration max-iterations)
        {:success true
         :provider model
         :turns turns-acc
         :work-log work-log
         :data {:choices [{:index 0
                           :message {:role "assistant"
                                     :content "Maximum iterations reached."
                                     :tool_calls nil}
                           :finish_reason "length"}]}}
        (let [_ (log-request "info" "Tool Loop" {:iteration iteration :calls (count (get-in current-payload [:messages]))} context)
              ;; Apply O-series normalization and loop pinning
              normalized-payload (openai/normalize-request
                                  current-payload
                                  (merge
                                   {:iteration iteration}
                                   (select-keys governance [:o-series-compat :loop-pinning :pin-temp :pin-effort])))
              resp (call-llm llm-url normalized-payload trace-ctx)]
          (if-not (:success resp)
            (assoc resp :turns turns-acc :work-log work-log)
            (let [choices (get-in resp [:data :choices])
                  message (get-in (first choices) [:message])
                  tool-calls (:tool_calls message)
                  provider model
                  entry (message->work-log-entry message provider model iteration)
                  _ (storage/append-turn! pii-salt entry)
                  turns-acc (conj turns-acc entry)]
              (if-not (seq tool-calls)
                (let [raw-content (:content message)
                      [scrubbed-content _new_vault] (redact-tool-output raw-content vault-state pii-salt governance)
                      final-resp (assoc-in resp [:data :choices 0 :message :content] scrubbed-content)]
                  (assoc final-resp :provider model :turns turns-acc :work-log work-log))
                (let [internal-calls (filter #(internal-call? (get-in % [:function :name])) tool-calls)
                      passthrough-calls (filter #(not (internal-call? (get-in % [:function :name]))) tool-calls)]
                  (if (seq internal-calls)
                    (let [[results new-vault new-discovered batch-work-log]
                          (execute-internal-batch internal-calls vault-state discovered-state mcp-servers governance context trace-ctx)
                          work-log (into work-log batch-work-log)
                          tool-turns (mapv (fn [res _tc]
                                             (let [te (tool-result->work-log-entry
                                                       (:tool_call_id res)
                                                       (:name res)
                                                       (:content res)
                                                       provider model iteration)]
                                               (storage/append-turn! pii-salt te)
                                               te))
                                           results internal-calls)
                          new-tools (vec (concat (config/get-meta-tool-definitions)
                                                 (map (fn [[name schema]]
                                                        {:type "function"
                                                         :function {:name name
                                                                    :description (:description schema)
                                                                    :parameters (:inputSchema schema)}})
                                                      new-discovered)))
                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
                          new-messages (into new-messages results)
                          [scrubbed-messages post-vault] (scrub-messages new-messages new-vault pii-salt governance)]
                      (recur (-> current-payload
                                 (assoc :messages scrubbed-messages)
                                 (assoc :tools new-tools))
                             post-vault
                             new-discovered
                             (inc iteration)
                             (into turns-acc tool-turns)
                             work-log))
                    (let [handoff-msg (prepare-passthrough-handoff message passthrough-calls vault-state mcp-servers governance)]
                      (assoc-in (assoc resp :provider model :turns turns-acc :work-log work-log)
                                [:data :choices 0 :message] handoff-msg))))))))))))

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

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(defn- extract-discovered-tools [messages]
  (reduce
   (fn [acc msg]
     (if (= "tool" (:role msg))
       (let [content (:content msg)
             parsed (try (json/parse-string (body->string content) true) (catch Exception _ nil))]
         (if (and parsed (:name parsed))
           (let [tool-name (:name parsed)
                 formatted-name (if (str/includes? tool-name "__")
                                  tool-name
                                  (str "mcp__" tool-name))]
             (assoc acc formatted-name parsed))
           acc))
       acc))
   {}
   messages))

(defn- prepare-llm-request [chat-req mcp-servers]
  (let [meta-tools (config/get-meta-tool-definitions)
        discovered-tools (extract-discovered-tools (:messages chat-req))
        existing-tools (:tools chat-req)
        fallbacks (config/get-llm-fallbacks mcp-servers)
        discovered-tool-defs (map (fn [[name schema]]
                                    {:type "function"
                                     :function {:name name
                                                :description (:description schema)
                                                :parameters (:inputSchema schema)}})
                                  discovered-tools)
        merged-tools (vec (concat (or existing-tools [])
                                  meta-tools
                                  discovered-tool-defs))
        extra-body (or (:extra_body chat-req) {})
        safe-extra (select-keys extra-body [:request-id :user :session-id :client-id])]
    (-> chat-req
        (merge safe-extra)
        (assoc :stream false :fallbacks fallbacks)
        (dissoc :stream_options)
        (update :messages (fn [msgs]
                            (mapv (fn [m]
                                    (if (and (= (:role m) "assistant") (:tool_calls m))
                                      (update m :tool_calls (fn [tcs]
                                                              (mapv #(dissoc % :index) tcs)))
                                      m))
                                  msgs)))
        (assoc :tools merged-tools))))

(defn- try-virtual-model-chain [config prepared-req llm-url mcp-servers max-iterations governance pii-salt trace-ctx]
  (let [chain (:chain config)
        retry-on (set (:retry-on config [429 500]))
        cooldown-mins (get config :cooldown-minutes 5)
        original-model (:model prepared-req)]
    (loop [providers (filter #(not (is-on-cooldown? %)) chain)
           last-error nil
           turns-acc []
           work-log []]
      (if (empty? providers)
        (let [final-error (or last-error {:message "All providers failed"})
              openclaw-error (translate-error-for-openclaw final-error 502)]
          {:success false
           :status 502
           :error (assoc openclaw-error :message "All providers failed")
           :turns turns-acc
           :work-log work-log})
        (let [provider (first providers)
              _ (log-request "info" "Virtual model: trying provider" {:provider provider :remaining (count (rest providers))}
                             {:model original-model :endpoint llm-url})
              req (-> prepared-req
                      (assoc :model provider)
                      (dissoc :fallbacks))
              result (agent-loop llm-url req mcp-servers max-iterations governance pii-salt trace-ctx)]
          (if (:success result)
            (assoc result :provider provider :turns (into turns-acc (:turns result)) :work-log (into work-log (:work-log result)))
            (if (some #(= % (:status result)) retry-on)
              (do
                (log-request "warn" "Virtual model: provider failed, setting cooldown" {:provider provider :status (:status result) :cooldown-mins cooldown-mins}
                             {:model original-model :endpoint llm-url})
                (set-cooldown! provider cooldown-mins)
                (track-provider-failure! provider (:status result))
                (recur (rest providers) (:error result) (into turns-acc (:turns result)) (into work-log (:work-log result))))
              (assoc result :provider provider :turns (into turns-acc (:turns result)) :work-log (into work-log (:work-log result))))))))))

(defn- extract-session-id [chat-req _payload request-id]
  (or (get-in chat-req [:extra_body :session-id])
      (get-in chat-req [:extra_body :user])
      (:user chat-req)
      request-id))

(defn- handle-chat-completion [request mcp-servers config trace-ctx]
  (let [start-nano (System/nanoTime)]
    (try
      (let [chat-req (parse-body (:body request))
            model (:model chat-req)
            request-id (or (:request-id chat-req) (str (java.util.UUID/randomUUID)))
            session-id (extract-session-id chat-req chat-req request-id)
            pii-salt (derive-pii-salt session-id)
            trunc-limit (:truncation-limit config 8192)
            context {:model model :request-id request-id :session-id pii-salt}
            _ (log-request "info" "Chat Completion Started" {:stream (:stream chat-req)} context)
            _ (when (= session-id request-id)
                (log-request "debug" "No session-id provided. Tokens will be request-scoped (unstable)." {} context))

          ;; --- Re-hydration ---
            history (storage/get-session pii-salt)
            projected-history (projection/project-work-log history model trunc-limit)
            history-messages (projection/work-log->openai-messages projected-history)
            _ (when (seq history-messages)
                (log-request "debug" "Re-hydrated session history" {:turns (count history-messages)} context))

            discovered (reduce (fn [acc [s-name s-conf]]
                                 (let [url (or (:url s-conf) (:uri s-conf))
                                       cmd (:cmd s-conf)]
                                   (if (or url cmd)
                                     (try (assoc acc s-name (mcp/discover-tools (name s-name) s-conf (:tools s-conf) (:policy (:governance config))))
                                          (catch Exception e
                                            (log-request "warn" "Discovery failed" {:server s-name :error (.getMessage e)} context)
                                            acc))
                                     acc)))
                               {} (:servers mcp-servers))

          ;; Prepend history to incoming messages
            messages (vec (concat history-messages (:messages chat-req)))
            injected-messages (config/inject-tools-into-messages messages mcp-servers discovered)

            llm-url (or (:llm-url config) (config/get-llm-url mcp-servers))
            virtual-models (config/get-virtual-models mcp-servers)
            virtual-config (or (get virtual-models model) (get virtual-models (keyword model)))
            prepared-req (prepare-llm-request (assoc chat-req :messages injected-messages :request-id request-id) mcp-servers)
            max-iter (or (:max-iterations config) 10)
            gov (:governance config)
            result (if virtual-config
                     (try-virtual-model-chain virtual-config prepared-req llm-url mcp-servers max-iter gov pii-salt trace-ctx)
                     (agent-loop llm-url prepared-req mcp-servers max-iter gov pii-salt trace-ctx))

            work-log (:work-log result [])
            turns-count (count (:turns result))
            tools-called (map :name work-log)
            ms (/ (- (System/nanoTime) start-nano) 1000000.0)
            trace-id (:trace-id trace-ctx)
            parent-id (:parent-id trace-ctx)]

        (if (:success result)
          (let [final-resp (:data result)
                actual-provider (:provider result)
                receipt-mode (or (:receipt-mode config) :on)
                receipt-suppressed? (false? (get-in chat-req [:extra_body :receipt] true))
                receipt (when-not (or (= :off receipt-mode) receipt-suppressed?)
                          (openai/build-receipt work-log {:trace-id trace-id
                                                          :receipt-mode receipt-mode}))
                _ (when receipt
                    (log-request "debug" "Generated action receipt" {:bytes (count receipt) :tools (count work-log) :mode receipt-mode} context))

                _ (record-completion! model actual-provider (:usage final-resp))
                _ (log-request "info" "Chat Completion Success" {:usage (:usage final-resp) :provider actual-provider} context)

                content (get-in final-resp [:choices 0 :message :content])
                final-content (if receipt (str receipt content) content)
                tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
                resp-headers (openai/build-response-headers {:session-id session-id
                                                             :turns turns-count
                                                             :tools-called tools-called
                                                             :ms ms
                                                             :trace-id trace-id
                                                             :parent-id parent-id})

                body (if (:stream chat-req)
                       (openai/build-chat-response-streaming
                        {:content final-content
                         :tool-calls tool-calls
                         :model model
                         :usage (:usage final-resp)})
                       (json/generate-string
                        (openai/build-chat-response
                         {:content final-content
                          :tool-calls tool-calls
                          :model model
                          :usage (:usage final-resp)})))]
            {:status 200 :headers (merge resp-headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")}) :body body})
          (let [status (or (:status result) 500)
                error-data (:error result)
                error-msg (if (map? error-data) (:message error-data) (str "Failed: " error-data))
                error-type (get-in result [:error :type] "internal_error")
                resp-headers (openai/build-response-headers {:session-id session-id
                                                             :turns turns-count
                                                             :tools-called tools-called
                                                             :ms ms
                                                             :trace-id trace-id
                                                             :parent-id parent-id})
                _ (log-request "warn" "Chat Completion Failed" {:status status :error error-msg :type error-type} (assoc context :endpoint llm-url))
                body (if (:stream chat-req)
                       (str "data: " (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}) "\n\ndata: [DONE]\n\n")
                       (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
            {:status status :headers (merge resp-headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")}) :body body})))
      (catch Exception e
        (let [err-data (ex-data e)
              status (or (:status err-data) 500)
              err-type (or (some-> err-data :type name) "internal_error")
              safe-msg (or (:message err-data) "Internal server error")]
          (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e) :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace e))} {})
          {:status status
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:error {:message safe-msg :type err-type}})})))))

(defn get-gateway-state []
  {:cooldowns @cooldown-state
   :usage @usage-stats
   :warming-up? (let [fut (get @server-state :warmup-future)]
                  (if fut (not (realized? fut)) false))})

(defn- handle-api [request _mcp-servers config]
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
      [:get "/api/v1/audit/verify"]
      (let [path (:audit-log-path config)
            secret (:audit-secret config)
            valid? (audit/verify-log (io/file path) secret)]
        {:status 200 :body (json/generate-string {:valid? valid? :path path})})
      {:status 404 :body (json/generate-string {:error "Not found"})})))

(defn- handler [request mcp-servers config]
  (let [request-id (str (java.util.UUID/randomUUID))
        governance (:governance config)
        audit-enabled (get-in governance [:audit :enabled] true)
        audit-conf (when audit-enabled
                     {:path (io/file (:audit-log-path config))
                      :secret (:audit-secret config)})
        headers (into {} (map (fn [[k v]] [(str/lower-case k) v]) (:headers request)))
        trace-ctx (openai/parse-traceparent (get headers "traceparent"))
        trace-context (or trace-ctx
                          {:trace-id (openai/generate-trace-id)
                           :parent-id (format "%016x" (rand-int Integer/MAX_VALUE))
                           :version "00"
                           :flags "00"})]
    (binding [*request-id* request-id
              *audit-config* audit-conf]
      (try
        (let [uri (:uri request)]
          (cond
            (= uri "/v1/chat/completions")
            (if (= :post (:request-method request))
              (handle-chat-completion request mcp-servers config trace-context)
              {:status 405 :body "Method not allowed"})
            (= uri "/health")
            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}
            (= uri "/stats")
            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:stats @usage-stats})}
            (str/starts-with? uri "/api/v1")
            (handle-api request mcp-servers config)
            :else
            {:status 404 :body "Not found"}))
        (catch Exception e
          (let [err-data (ex-data e)
                status (or (:status err-data) 500)
                err-type (or (some-> err-data :type name) "internal_error")
                safe-msg (or (:message err-data) "Internal server error")]
            (log-request "error" "Request failed" {:type err-type :message (.getMessage e)} {:endpoint (:uri request)})
            {:status status
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:error {:message safe-msg
                                                  :type err-type}})}))))))

(defn start-server [mcp-config]
  (let [port (or (:port mcp-config)
                 (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
                 8080)
        host (or (:host mcp-config)
                 (System/getenv "MCP_INJECTOR_HOST")
                 "127.0.0.1")
        llm-url (or (:llm-url mcp-config)
                    (System/getenv "MCP_INJECTOR_LLM_URL")
                    "http://localhost:11434")
        log-level (or (:log-level mcp-config)
                      (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
        max-iterations (or (:max-iterations mcp-config)
                           (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
                           10)
        mcp-config-path (or (:mcp-config-path mcp-config)
                            (System/getenv "MCP_INJECTOR_MCP_CONFIG")
                            "mcp-servers.edn")
        audit-log-path (or (:audit-log-path mcp-config)
                           (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
                           "logs/audit.log.ndjson")
        audit-secret (config/resolve-secure-secret "INJECTOR_AUDIT_SECRET" "audit-secret" config/MIN_SECRET_LENGTH log-request)
        eval-timeout-ms (or (:eval-timeout-ms mcp-config)
                            (some-> (System/getenv "MCP_INJECTOR_EVAL_TIMEOUT_MS") not-empty Integer/parseInt)
                            5000)
        base-mcp-servers (cond
                           (and (map? mcp-config) (:servers mcp-config)) mcp-config
                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
                           :else (config/load-mcp-servers mcp-config-path))
        provided-governance (config/extract-governance mcp-config)
        _ (when provided-governance
            (log-request "info" "Governance source resolved" {:source (:source provided-governance)}))
        mcp-servers (if (map? mcp-config)
                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
                        (if-let [gov (:governance mcp-config)]
                          (assoc merged :governance gov)
                          merged))
                      base-mcp-servers)
        unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
        final-governance (config/resolve-governance (assoc mcp-servers :governance (:config provided-governance)) unified-env)
        final-config {:port port :host host :llm-url llm-url :log-level log-level
                      :max-iterations max-iterations :mcp-config-path mcp-config-path
                      :audit-log-path audit-log-path :audit-secret audit-secret
                      :receipt-mode (or (:receipt-mode mcp-config)
                                        (some-> (System/getenv "MCP_INJECTOR_RECEIPT_MODE") not-empty keyword)
                                        :on)
                      :receipt-style (or (:receipt-style mcp-config)
                                         (some-> (System/getenv "MCP_INJECTOR_RECEIPT_STYLE") not-empty keyword)
                                         :emoji)
                      :footer-mode (or (:footer-mode mcp-config)
                                       (some-> (System/getenv "MCP_INJECTOR_FOOTER_MODE") not-empty keyword)
                                       :off)
                      :governance (assoc final-governance :eval-timeout-ms eval-timeout-ms)}
        _ (policy/validate-policy! (:policy final-governance))
        _ (let [policy-rules (:policy final-governance)
                allow-list (:allow policy-rules)]
            (when (and allow-list
                       (some #(or (= % "clojure-eval")
                                  (and (string? %) (str/includes? % "clojure-eval")))
                             allow-list))
              (binding [*audit-config* {:path (io/file audit-log-path) :secret audit-secret}
                        *request-id* "startup-security"]
                (log-request "critical"
                             "clojure-eval is ENABLED - escape hatch with full JVM access"
                             {:feature "clojure-eval" :risk "RCE-by-design"}
                             {:mode :startup}))
              (println "WARNING: clojure-eval is ENABLED - full JVM code execution allowed.")))
        _ (when (get-in final-governance [:audit :enabled] true)
            (audit/init-audit! audit-log-path))
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
      (mcp/clear-tool-cache!)
      (audit/close-audit!))))

(defn clear-mcp-sessions! []
  (mcp/clear-tool-cache!))

(defn -main [& _args]
  (let [initial-config (config/load-config)
        mcp-servers (config/load-mcp-servers (:mcp-config initial-config))
        unified-config (config/get-config mcp-servers)]
    (start-server unified-config)))
