(ns mcp-injector.openai-compat
  "OpenAI API compatibility layer and SSE streaming."
  (:require [cheshire.core :as json]))

(defn parse-chat-request
  "Parse incoming OpenAI chat completion request"
  [body]
  (let [parsed (json/parse-string body true)]
    {:model (:model parsed "gpt-4o-mini")
     :messages (:messages parsed [])
     :stream (get parsed :stream false)
     :temperature (:temperature parsed)
     :max-tokens (:max_tokens parsed)}))

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
