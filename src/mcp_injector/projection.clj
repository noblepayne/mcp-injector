(ns mcp-injector.projection
  (:require [clojure.string :as str]))

;; --- Projection Functions ---

(def ^:private receipt-delimiter "\n\n---\n\n")

(defn truncate-text [text limit]
  (if (and limit (> (count text) limit))
    (str (subs text 0 limit)
         "\n[truncated: " (count text) " bytes total, limit " limit "]")
    text))

(defn strip-receipt
  "Strip action receipt from assistant message content.
    Pattern: Remove text from start up to and including receipt-delimiter.
    This prevents 'History Poisoning' where the LLM sees its own tracing metadata."
  [content]
  (if (string? content)
    (if-let [idx (str/index-of content receipt-delimiter)]
      (subs content (+ idx (count receipt-delimiter)))
      content)
    content))

(defn project-content-block [block outbound-provider source-provider truncation-limit]
  (case (:type block)
    "thinking"
    (if (= source-provider outbound-provider)
      block
      {:type "text"
       :text "[reasoning from prior turn omitted: provider mismatch]"})

    "tool_result"
    (if (and (:content block) (seq (:content block)))
      (update-in block [:content 0 :text] #(truncate-text % truncation-limit))
      block)

    block))

(defn project-entry
  "Transform a WorkLog entry for external use by a specific provider.
   Strips receipts from assistant messages to prevent LLM hallucination."
  ([entry outbound-provider]
   (project-entry entry outbound-provider 8192))
  ([entry outbound-provider truncation-limit]
   (let [source-provider (get-in entry [:_meta :provider])
         content (:content entry)
         role (:role entry)
         processed-content (mapv (fn [block]
                                   (if (and (= role "assistant") (= (:type block) "text"))
                                     (update block :text strip-receipt)
                                     block))
                                 content)]
     (-> entry
         (dissoc :_meta)
         (assoc :content (mapv #(project-content-block % outbound-provider source-provider truncation-limit)
                               processed-content))))))

(defn project-work-log
  "Project an entire WorkLog vector for an outbound provider."
  ([work-log outbound-provider]
   (project-work-log work-log outbound-provider 8192))
  ([work-log outbound-provider truncation-limit]
   (mapv #(project-entry % outbound-provider truncation-limit) work-log)))

(defn work-log->openai-messages
  "Convert a projected WorkLog into OpenAI-compatible message maps."
  [projected-work-log]
  (mapv (fn [entry]
          {:role (:role entry)
           :content (:content entry)})
        projected-work-log))
