(ns mcp-injector.test-llm-server
  "Simulates Bifrost (LLM gateway) for integration testing.
   Returns predetermined responses to test agent loop behavior.
   Supports success responses, error responses, and timeouts."
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]))

(def ^:private server-state (atom nil))

(defn- build-success-response
  "Build a successful OpenAI-compatible response"
  [request-body response-data]
  {:id (str "test-" (java.util.UUID/randomUUID))
   :object "chat.completion"
   :created (quot (System/currentTimeMillis) 1000)
   :model (get request-body :model "gpt-4o-mini")
   :choices [{:index 0
              :message {:role "assistant"
                        :content (:content response-data)
                        :tool_calls (when (:tool_calls response-data)
                                      (map-indexed
                                       (fn [idx tc]
                                         {:id (str "call_" idx)
                                          :type "function"
                                          :index idx
                                          :function {:name (:name tc)
                                                     :arguments (json/generate-string (:arguments tc))}})
                                       (:tool_calls response-data)))}
              :finish_reason (if (:tool_calls response-data) "tool_calls" "stop")}]
   :usage (or (:usage response-data)
              {:prompt_tokens 10
               :completion_tokens 5
               :total_tokens 15})})

(defn- build-error-response
  "Build an error response"
  [status message]
  {:error {:message message
           :type (case status
                   429 "rate_limit_exceeded"
                   500 "internal_server_error"
                   503 "service_unavailable"
                   "unknown_error")
           :param nil
           :code (case status
                   429 "rate_limit_exceeded"
                   500 "internal_server_error"
                   503 "service_unavailable"
                   nil)}})

(defn- handle-chat-completion
  "Handle OpenAI chat completion request"
  [request]
  (let [body (json/parse-string (slurp (:body request)) true)]
    (swap! (:received-requests @server-state) conj body)

    ;; Get next response config from queue
    (let [response-config (or (first @(:responses @server-state))
                              {:type :success
                               :data {:role "assistant"
                                      :content "This is a default test response."}})]
      ;; Remove used response from queue
      (swap! (:responses @server-state) rest)

      ;; Handle based on response type
      (case (:type response-config)
        :error {:status (:status response-config 500)
                :headers {"Content-Type" "application/json"}
                :body (json/generate-string
                       (build-error-response
                        (:status response-config 500)
                        (:message response-config "Internal server error")))}

        :timeout ;; Will be handled by the delay mechanism in handler
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string (build-success-response body (:data response-config)))}

        ;; Default: success
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string (build-success-response body (:data response-config)))}))))

(defn handler
  "HTTP handler for Bifrost simulator"
  [request]
  (if (and (= :post (:request-method request))
           (= "/v1/chat/completions" (:uri request)))
    ;; Check if we need to delay (timeout simulation)
    (let [response-config (first @(:responses @server-state))]
      (if (= :timeout (:type response-config))
        ;; Simulate timeout by sleeping
        (do
          (Thread/sleep (:delay-ms response-config 35000))
          (handle-chat-completion request))
        (handle-chat-completion request)))
    {:status 404
     :body "Not found"}))

(defn start-server
  "Start test Bifrost server on random port.
   Returns map with :port, :stop function, :received-requests, and control functions"
  []
  (let [received-reqs (atom [])
        responses (atom [])
        srv (http/run-server handler {:port 0})
        port (:local-port (meta srv))]
    (reset! server-state {:server srv
                          :port port
                          :received-requests received-reqs
                          :responses responses})
    {:port port
     :stop srv
     :received-requests received-reqs
     :set-response! (fn [response]
                      (swap! responses conj response))
     :clear-responses! (fn [] (reset! responses []))}))

(defn stop-server
  "Stop the test Bifrost server"
  [{:keys [stop]}]
  (stop))

(defn set-next-response
  "Convenience function to queue a success response"
  [server response-data]
  ((:set-response! server) {:type :success :data response-data}))

(defn set-error-response
  "Set an error response with specific status code"
  [server status message]
  ((:set-response! server) {:type :error
                            :status status
                            :message message}))

(defn set-timeout-response
  "Set a response that will delay (simulating timeout)"
  ([server]
   (set-timeout-response server 35000))
  ([server delay-ms]
   ((:set-response! server) {:type :timeout
                             :delay-ms delay-ms
                             :data {:role "assistant"
                                    :content "This response is too late"}})))

(defn set-tool-call-response
  "Set a response that includes tool calls"
  [server tool-calls]
  (set-next-response server
                     {:role "assistant"
                      :tool_calls tool-calls}))

(defn set-response-with-usage
  "Set a success response with specific usage stats"
  [server response-data usage]
  ((:set-response! server) {:type :success
                            :data (assoc response-data :usage usage)}))

(defn clear-responses
  "Clear all queued responses"
  [server]
  ((:clear-responses! server)))
