(ns mcp-injector.projection)

;; --- Projection Functions ---

(defn- truncate-text [text limit]
  (if (and limit (> (count text) limit))
    (str (subs text 0 limit)
         "\n[truncated: " (count text) " bytes total, limit " limit "]")
    text))

(defn- project-content-block [block outbound-provider source-provider truncation-limit]
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

    ;; Pass-through for text, tool_use, etc.
    block))

(defn project-entry
  "Transform a WorkLog entry for external use by a specific provider."
  ([entry outbound-provider]
   (project-entry entry outbound-provider 8192))
  ([entry outbound-provider truncation-limit]
   (let [source-provider (get-in entry [:_meta :provider])
         content (:content entry)]
     (-> entry
         (dissoc :_meta)
         (assoc :content (mapv #(project-content-block % outbound-provider source-provider truncation-limit) content))))))

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
