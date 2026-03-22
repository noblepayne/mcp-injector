(ns mcp-injector.openai-compat
  "OpenAI API compatibility layer and SSE streaming."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [mcp-injector.pii :as pii]))

(defn parse-chat-request
  "Parse incoming OpenAI chat completion request"
  [body]
  (let [parsed (json/parse-string body true)]
    {:model (:model parsed "gpt-4o-mini")
     :messages (:messages parsed [])
     :stream (get parsed :stream false)
     :temperature (:temperature parsed)
     :max-tokens (:max_tokens parsed)}))

;; ═══════════════════════════════════════════════════════════════
;; Stage 7: O-Series Reasoning Model Normalization
;; ═══════════════════════════════════════════════════════════════

(def ^:private o-series-pattern
  "Regex matching O-series model base names (o1, o3, o4-mini, o3-pro, o-2024-12-17, etc.).
   Catches current and future reasoning models. Matched against the base model
   name after stripping provider path (e.g., 'openrouter/openai/o4-mini' -> 'o4-mini')."
  #"o[\d\-][0-9a-z\-]*")

(defn o-series?
  "Check if model is an OpenAI O-series reasoning model.
   Matches: o1, o3, o4-mini, o3-pro, and future o-series models.
   Handles provider-prefixed names like 'openrouter/openai/o1-mini'.
   Returns strict boolean."
  [model]
  (boolean
   (when model
     (re-matches o-series-pattern
                 (last (str/split (str model) #"/"))))))

(defn o1-preview?
  "Check for o1-preview models. Deprecated July 2025, kept for self-hosted/proxy compat.
   Handles provider-prefixed names. Returns strict boolean."
  [model]
  (boolean
   (when model
     (str/starts-with? (last (str/split (str model) #"/")) "o1-preview"))))

(defn normalize-request
  "Normalize an OpenAI-compatible request for the target model.
   Handles O-series schema changes (developer role, max_completion_tokens,
   unsupported param stripping). Preserves fields like include_search and
   plugins for upstream providers.
   
   Options:
   - :o-series-compat - enable O-series normalization (default: true)
   - :loop-pinning - enable loop-specific overrides (default: false)
   - :pin-temp - temperature to use during loop turns
   - :pin-effort - reasoning_effort to use during loop turns
   - :iteration - current iteration number (for loop pinning)
   
   O-series models reject temperature, top_p, frequency_penalty, and other
   sampling params. Stripping applies to ALL o-series models, not just o1-preview.
   Loop pinning skips temperature for o-series (only reasoning_effort is pinned)."
  ([chat-req]
   (normalize-request chat-req {}))
  ([chat-req {:keys [o-series-compat loop-pinning pin-temp pin-effort iteration]
              :or {o-series-compat true
                   loop-pinning false
                   pin-temp 0.1
                   pin-effort :low
                   iteration 0}}]
   (let [model (:model chat-req)
         is-o-series (and o-series-compat (o-series? model))

         ;; Apply O-series normalizations
         normalized
         (if is-o-series
           (-> chat-req
               ;; Rename system role to developer
               (update :messages
                       (fn [messages]
                         (mapv (fn [msg]
                                 (if (= "system" (:role msg))
                                   (assoc msg :role "developer")
                                   msg))
                               messages)))
               ;; Rename max_tokens to max_completion_tokens (only if not already present)
               (cond-> (and (:max_tokens chat-req)
                            (not (contains? chat-req :max_completion_tokens)))
                 (assoc :max_completion_tokens (:max_tokens chat-req)))
               (dissoc :max_tokens)
               ;; Strip unsupported params for ALL o-series models
               ;; (temperature, top_p, frequency_penalty, etc. are rejected by the API)
               (dissoc :temperature :top_p :frequency_penalty
                       :presence_penalty :n :best_of :logprobs
                       :top_logprobs :logit_bias :stop))
           chat-req)

         ;; Apply loop pinning if enabled and not final iteration
         ;; Skip temperature for o-series (API rejects it) — only pin reasoning_effort
         pinned
         (if (and loop-pinning (> iteration 0))
           (cond-> normalized
             (not is-o-series)
             (assoc :temperature pin-temp)
             :always
             (assoc :reasoning_effort pin-effort))
           normalized)]

     ;; Ensure include_search and plugins are preserved
     pinned)))

;; ═══════════════════════════════════════════════════════════════

(defn- sse-event
  "Format data as SSE event"
  [data]
  (str "data: " data "\n\n"))

(defn sse-done
  "SSE termination event"
  []
  (sse-event "[DONE]"))

(defn build-chat-response
  "Build OpenAI-compatible chat completion response"
  [{:keys [content tool-calls model usage]}]
  {:id (str "chatcmpl-" (java.util.UUID/randomUUID))
   :object "chat.completion"
   :created (quot (System/currentTimeMillis) 1000)
   :model model
   :choices [{:index 0
              :message {:role "assistant"
                        :content content
                        :tool_calls tool-calls}
              :finish_reason (if tool-calls "tool_calls" "stop")}]
   :usage (or usage
              {:prompt_tokens 0
               :completion_tokens 0
               :total_tokens 0})})

(defn build-chat-response-streaming
  "Build SSE stream of chat completion response"
  [{:keys [content tool-calls model usage]}]
  (let [response-id (str "chatcmpl-" (java.util.UUID/randomUUID))
        created (quot (System/currentTimeMillis) 1000)]
    (str
      ;; Initial response
     (sse-event (json/generate-string
                 {:id response-id
                  :object "chat.completion.chunk"
                  :created created
                  :model model
                  :choices [{:index 0
                             :delta {:role "assistant"}
                             :finish_reason nil}]}))

      ;; Content chunks
     (when content
       (sse-event (json/generate-string
                   {:id response-id
                    :object "chat.completion.chunk"
                    :created created
                    :model model
                    :choices [{:index 0
                               :delta {:content content}
                               :finish_reason nil}]})))

      ;; Tool calls if present
     (when tool-calls
       (sse-event (json/generate-string
                   {:id response-id
                    :object "chat.completion.chunk"
                    :created created
                    :model model
                    :choices [{:index 0
                               :delta {:tool_calls tool-calls}
                               :finish_reason nil}]})))

      ;; Final chunk
     (sse-event (json/generate-string
                 {:id response-id
                  :object "chat.completion.chunk"
                  :created created
                  :model model
                  :choices [{:index 0
                             :delta {}
                             :finish_reason (if tool-calls "tool_calls" "stop")}]
                  :usage usage}))

      ;; Done
     (sse-done))))

(defn send-sse-response
  "Send response as Server-Sent Events"
  [response-data]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"}
   :body (build-chat-response-streaming response-data)})

(defn generate-trace-id
  "Generate a W3C-compliant 32-hex-character trace ID.
   Uses 16 hex chars of timestamp + 16 hex chars of random for consistency."
  []
  (let [timestamp (format "%016x" (quot (System/currentTimeMillis) 1000))
        random-part (let [sb (StringBuilder.)]
                      (doseq [_ (range 16)]
                        (.append sb (format "%01x" (rand-int 16))))
                      (str sb))]
    (str timestamp random-part)))

(defn parse-traceparent
  "Parse W3C traceparent header value.
   Format: version-trace_id-parent_id-flags
   Returns {:version :trace-id :parent-id :flags} or nil if invalid."
  [traceparent]
  (when (string? traceparent)
    (let [parts (str/split traceparent #"-")]
      (when (= 4 (count parts))
        (let [[version trace-id parent-id flags] parts]
          (when (and (= 2 (count version))
                     (= 32 (count trace-id))
                     (= 16 (count parent-id))
                     (= 2 (count flags)))
            {:version version
             :trace-id trace-id
             :parent-id parent-id
             :flags flags}))))))

(defn build-traceparent
  "Build W3C traceparent header value.
   version: 2 hex chars (00 = spec version)
   trace-id: 32 hex chars
   parent-id: 16 hex chars
   flags: 2 hex chars (00 = no flags, 01 = sampled)"
  [trace-id parent-id]
  (str "00-" trace-id "-" parent-id "-00"))

(defn build-trace-headers
  "Generate W3C traceparent headers for distributed tracing.
   If incoming-headers contains traceparent, propagate it.
   Otherwise generate new trace context.
   Returns map of headers to add to requests."
  ([]
   (build-trace-headers nil))
  ([incoming-headers]
   (let [existing (some->> (or (get incoming-headers "traceparent")
                               (get incoming-headers "Traceparent"))
                           parse-traceparent)
         trace-id (or (:trace-id existing) (generate-trace-id))
         parent-id (format "%016x" (rand-int Integer/MAX_VALUE))
         traceparent (build-traceparent trace-id parent-id)]
     {"traceparent" traceparent})))

(defn build-response-headers
  "Build X-Injector-* and Server-Timing response headers.
   Safe to call with nil — always returns at least version header.
   
   Options:
   - :session-id - for X-Injector-Session
   - :turns - number of LLM turns
   - :tools-called - vector of tool names called
   - :ms - total wall time in ms (can be fractional for precision)
   - :trace-id - for X-Injector-Traceparent
   - :parent-id - for X-Injector-Traceparent
   
   Returns map of header-name -> header-value."
  [{:keys [session-id turns tools-called ms trace-id parent-id]
    :or {ms 0}}]
  (let [headers {"X-Injector-Version" "1"}]
    (cond-> headers
      session-id
      (assoc "X-Injector-Session" (str session-id))

      (some? turns)
      (assoc "X-Injector-Turns" (str turns))

      (seq tools-called)
      (assoc "X-Injector-Tools"
             (let [s (str/join "," tools-called)]
               (if (> (count s) 2000)
                 (str (subs s 0 1997) "...")
                 s)))

      (some? ms)
      (merge {"X-Injector-Ms" (str ms)
              "Server-Timing" (str "total;dur=" (format "%.2f" ms))})

      (and trace-id parent-id)
      (assoc "X-Injector-Traceparent" (build-traceparent trace-id parent-id)))))

(defn redact-for-receipt
  "Redact PII from tool arguments for safe inclusion in receipts.
   Uses the PII scanner redact-data with request-scoped vault.
   Returns [redacted-args vault detected]."
  [args pii-config vault]
  (if (map? args)
    (pii/redact-data args pii-config vault)
    [args vault []]))

(defn format-tool-entry
  "Format a single tool execution for the receipt.
  Returns [tool-name args-display ms error-msg status] or nil if should be excluded.
  Args-display is the formatted args string (truncated if needed).
  Status is :ok or :error."
  [{:keys [name args ms status error]}]
  (when (and name (not= status :skipped))
    (let [;; Redact PII from args before formatting
          [redacted-args _ _] (if args
                                (redact-for-receipt args {:patterns mcp-injector.pii/default-patterns} {})
                                [nil {} []])
          args-str (when redacted-args
                     (let [s (if (string? redacted-args) redacted-args (json/generate-string redacted-args))]
                       (if (> (count s) 60)
                         (str (subs s 0 60) "…")
                         s)))
          args-display (or args-str "{}")
          ms-str (if ms (str ms "ms") "?")
          entry [name args-display ms-str error status]]
      (with-meta entry {:status status}))))

(defn build-receipt
  "Build prepended action receipt for agent execution.
     
   Takes a worklog vector of tool execution maps:
   - {:name stripe.get_customer :args {:id cus_123} :ms 45 :status :ok}
   - {:name postgres.query :args {:sql SELECT *} :ms 120 :status :error :error timeout}
   
   Options map:
   - :trace-id - trace ID to include in receipt header (required for idempotency)
   - :receipt-mode - :on (default, always show when tools ran), :off (never show), :errors-only (show only if errors)
   - :receipt-style - :emoji (use ✓/✗ symbols) or :ascii (text labels)
   
   Returns prepended receipt string or nil if no receipt should be shown.
   
   Format (emoji style):
   🔧 2 tool calls · 165ms
   
   `stripe.get_customer` {\"customer_id\": \"cus_123\"} ✓ 45ms
   `postgres.query` {\"sql\": \"SELECT * ...\"} ✗ 120ms — timeout
   
   ---
   "
  ([worklog]
   (build-receipt worklog {}))
  ([worklog {:keys [trace-id receipt-mode receipt-style]
             :or {receipt-mode :on receipt-style :ascii}}]
   (let [_trace-id (or trace-id (generate-trace-id))
         raw-entries (->> worklog
                          (keep format-tool-entry)
                          seq)
         entries (case receipt-mode
                   :off nil
                   :errors-only (when (some #(= :error (-> % meta :status)) raw-entries)
                                  raw-entries)
                   :on raw-entries
                   raw-entries)
         emoji? (= receipt-style :emoji)
         check (if emoji? "✓" "OK")
         cross (if emoji? "✗" "ERR")
         wrench (if emoji? "🔧 " "[TOOLS] ")]
     (when entries
       (let [total-ms (apply + (map :ms worklog))
             total-ms-str (str total-ms "ms")
             tool-count (count entries)
             header (str wrench tool-count " tool call" (when (> tool-count 1) "s")
                         (when total-ms (str " · " total-ms-str))
                         "\n\n")
             lines (mapv (fn [[name args-display ms-str error status]]
                           (let [suffix (if (= status :ok)
                                          (str check " " ms-str)
                                          (str cross " " ms-str " — " error))]
                             (str "`" name "` " args-display " " suffix)))
                         entries)
             content (str/join (str \newline) lines)]
         (str header content "\n\n---\n\n"))))))
