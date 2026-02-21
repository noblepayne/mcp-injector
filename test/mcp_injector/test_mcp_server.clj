(ns mcp-injector.test-mcp-server
  "Real http-kit MCP server for integration testing.
   Implements enough of the MCP protocol to test mcp-injector."
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]))

(defn- body-to-sse
  "Convert JSON-RPC response body to SSE format"
  [json-body-str]
  (str "event: message\r\n"
       "data: " json-body-str "\r\n"
       "\r\n"))

(defn- handle-mcp-request
  "Handle incoming MCP JSON-RPC request"
  [request state]
  (let [body-str (slurp (:body request))
        body (json/parse-string body-str true)
        method (get-in body [:params :method] (:method body))
        headers (:headers request)
        session-id (or (get headers "mcp-session-id")
                       (get headers "Mcp-Session-Id")
                       (get headers :mcp-session-id))
        require-session? (:require-session state)]
    (swap! (:received-requests state) conj {:body body :headers headers})

    (cond
      ;; Initialize method - always allowed, creates session
      (= method "initialize")
      (let [new-session-id (str (java.util.UUID/randomUUID))]
        {:status 200
         :headers {"content-type" "application/json"
                   "mcp-session-id" new-session-id}
         :body {:jsonrpc "2.0"
                :id (:id body)
                :result {:protocolVersion "2025-03-26"
                         :capabilities {}
                         :serverInfo {:name "test-mcp" :version "1.0.0"}}}})

      ;; Missing session when required (only for protected methods)
      (and require-session? (not session-id)
           (not= method "notifications/initialized"))
      {:status 400
       :headers {"content-type" "application/json"}
       :body {:error "Missing session ID"}}

      ;; Normal methods
      :else
      (case method
        "notifications/initialized"
        {:status 202 :body nil}

        "tools/list"
        {:status 200
         :headers {"content-type" "application/json"}
         :body {:jsonrpc "2.0"
                :id (:id body)
                :result {:tools (let [tools @(:tools state)]
                                  (if (map? tools)
                                    (map (fn [[name schema]]
                                           {:name (clojure.core/name name)
                                            :description (:description schema)
                                            :inputSchema (:schema schema)})
                                         tools)
                                    (map (fn [tool]
                                           {:name (:name tool)
                                            :description (:description tool)
                                            :inputSchema (:inputSchema tool)})
                                         tools)))}}}

        "tools/call"
        (let [tool-name (get-in body [:params :name])
              args (get-in body [:params :arguments])
              tools @(:tools state)
              handler (or (get-in tools [tool-name :handler])
                          (get-in tools [(keyword tool-name) :handler]))]
          {:status 200
           :headers {"content-type" "application/json"}
           :body {:jsonrpc "2.0"
                  :id (:id body)
                  :result (if handler
                            {:content [{:type "text"
                                        :text (json/generate-string (handler args))}]}
                            {:content [{:type "text"
                                        :text (json/generate-string {:error (str "Tool not found: " tool-name)})}]
                             :isError true})}})

        ;; Unknown method
        {:status 404
         :headers {"content-type" "application/json"}
         :body {:jsonrpc "2.0"
                :id (:id body)
                :error {:code -32601
                        :message (str "Method not found: " method)}}}))))

(defn handler
  "HTTP handler for MCP server"
  [request state]
  (if (= :post (:request-method request))
    (let [resp (handle-mcp-request request state)
          status (:status resp)
          body (:body resp)
          resp-headers (:headers resp)
          sse-mode? (and (= :sse (:response-mode state))
                         (not= 202 status))]
      (cond
        ;; 202 No Content - notifications
        (= 202 status)
        {:status 202 :body ""}

        ;; SSE mode - wrap body in SSE format
        sse-mode?
        (let [json-body (if (string? body) body (json/generate-string body))]
          {:status 200
           :headers (merge resp-headers
                           {"content-type" "text/event-stream"
                            "cache-control" "no-cache"})
           :body (body-to-sse json-body)})

        ;; Normal JSON mode
        :else
        {:status status
         :headers (merge {"content-type" "application/json"} resp-headers)
         :body (if (string? body) body (json/generate-string body))}))
    {:status 405
     :body "Method not allowed"}))

(defn start-server
  "Start test MCP server on random port.
   Returns map with :port, :stop function, :received-requests atom"
  [& {:keys [tools require-session response-mode]}]
  (let [received-reqs (atom [])
        state {:received-requests received-reqs
               :tools (atom (or tools {}))
               :require-session require-session
               :response-mode (or response-mode :json)}
        srv (http/run-server (fn [req] (handler req state)) {:port 0})
        port (:local-port (meta srv))]
    {:port port
     :stop srv
     :received-requests received-reqs
     :set-tools! (fn [new-tools] (reset! (:tools state) new-tools))}))

(defn stop-server
  "Stop the test MCP server"
  [{:keys [stop]}]
  (stop))

(defn- default-stripe-tools
  "Default Stripe tools for testing"
  []
  {:retrieve_customer
   {:description "Retrieve a customer from Stripe"
    :schema {:type "object"
             :properties {:customer_id {:type "string"}}
             :required ["customer_id"]}
    :handler (fn [args]
               {:id (:customer_id args)
                :email "customer@example.com"
                :name "Test Customer"})}

   :list_charges
   {:description "List charges from Stripe"
    :schema {:type "object"
             :properties {:customer {:type "string"}
                          :limit {:type "integer"}}
             :required []}
    :handler (fn [_]
               [{:id "ch_123" :amount 1000 :currency "usd"}])}})

(defn start-test-mcp-server
  "Convenience function to start test MCP server with default Stripe tools"
  [& {:keys [response-mode]}]
  (start-server :tools (default-stripe-tools) :response-mode response-mode))
