(ns mcp-injector.schemas
  (:require [malli.core :as m]
            [malli.error :as me]))

;; --- Content Blocks ---

(def TextBlock
  [:map
   [:type [:= "text"]]
   [:text :string]
   [:_synthetic {:optional true} :boolean]])

(def ThinkingBlock
  [:map
   [:type [:= "thinking"]]
   [:thinking :string]
   [:signature :string]])

(def ToolUseBlock
  [:map
   [:type [:= "tool_use"]]
   [:id :string]
   [:name :string]
   [:input :map]])

(def ToolResultBlock
  [:map
   [:type [:= "tool_result"]]
   [:tool_use_id :string]
   [:content [:vector TextBlock]]])

(def ContentBlock
  [:or TextBlock ThinkingBlock ToolUseBlock ToolResultBlock])

;; --- WorkLog Entry ---

(def EntryMeta
  [:map
   [:provider :string]
   [:model :string]
   [:turn-index :int]
   [:timestamp :string] ; ISO-8601
   [:truncated? {:optional true} :boolean]])

(def WorkLogEntry
  [:map
   [:role [:enum "assistant" "tool"]]
   [:content [:vector ContentBlock]]
   [:_meta EntryMeta]])

;; --- Helpers ---

(defn validate-entry [entry]
  (if (m/validate WorkLogEntry entry)
    entry
    (throw (ex-info "Invalid WorkLogEntry"
                    {:error (me/humanize (m/explain WorkLogEntry entry))
                     :entry entry}))))
