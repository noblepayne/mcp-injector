(ns mcp-injector.schemas
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; --- Content Blocks ---

(s/def :mcp/type #{"text" "thinking" "tool_use" "tool_result"})

(s/def :mcp/text string?)
(s/def :mcp/_synthetic boolean?)
(s/def ::TextBlock
  (s/keys :req-un [:mcp/type :mcp/text]
          :opt-un [:mcp/_synthetic]))

(s/def :mcp/thinking string?)
(s/def :mcp/signature string?)
(s/def ::ThinkingBlock
  (s/keys :req-un [:mcp/type :mcp/thinking :mcp/signature]))

(s/def :mcp/id string?)
(s/def :mcp/name string?)
(s/def :mcp/input map?)
(s/def ::ToolUseBlock
  (s/keys :req-un [:mcp/type :mcp/id :mcp/name :mcp/input]))

(s/def :mcp/tool_use_id string?)
(s/def :mcp/content (s/coll-of ::TextBlock :kind vector?))
(s/def ::ToolResultBlock
  (s/keys :req-un [:mcp/type :mcp/tool_use_id :mcp/content]))

(s/def ::ContentBlock
  (s/or :text ::TextBlock
        :thinking ::ThinkingBlock
        :tool_use ::ToolUseBlock
        :tool_result ::ToolResultBlock))

;; --- WorkLog Entry ---

(s/def :meta/provider string?)
(s/def :meta/model string?)
(s/def :meta/turn-index int?)
(s/def :meta/timestamp string?)
(s/def :meta/truncated? boolean?)

(s/def ::EntryMeta
  (s/keys :req-un [:meta/provider :meta/model :meta/turn-index :meta/timestamp]
          :opt-un [:meta/truncated?]))

(s/def :entry/role #{"assistant" "tool"})
(s/def :entry/content (s/coll-of ::ContentBlock :kind vector?))
(s/def :entry/_meta ::EntryMeta)

(s/def ::WorkLogEntry
  (s/keys :req-un [:entry/role :entry/content :entry/_meta]))

;; --- Helpers ---

(defn- humanize [explain-data]
  (if-let [problems (:clojure.spec.alpha/problems explain-data)]
    (->> problems
         (map (fn [p]
                (let [path (if (empty? (:in p)) ["root"] (:in p))
                      path-str (str/join "." (map (fn [x] (if (or (keyword? x) (symbol? x)) (name x) (str x))) path))]
                  [path-str (str "failed pred: " (pr-str (:pred p)) " val: " (pr-str (:val p)))])))
         (into {}))
    {}))

(defn validate-entry [entry]
  (if (s/valid? ::WorkLogEntry entry)
    entry
    (let [ed (s/explain-data ::WorkLogEntry entry)]
      (throw (ex-info "Invalid WorkLogEntry"
                      {:error (humanize ed)
                       :entry entry})))))
