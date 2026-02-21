(ns mcp-injector.test-mcp-server
  "Real http-kit MCP server for integration testing.
   Implements enough of the MCP protocol to test mcp-injector."
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]))

(def ^:private server-state (atom nil))

(defn- handle-mcp-request
  "Handle incoming MCP JSON-RPC request"
  [request]
  (let [body-str (slurp (:body request))
        body (json/parse-string body-str true)
        method (get-in body [:params :method] (:method body))
        headers (:headers request)
        session-id (or (get headers "mcp-session-id")
                       (get headers "Mcp-Session-Id")
                       (get headers :mcp-session-id))
        require-session? (:require-session @server-state)]
    (swap! (:received-requests @server-state) conj {:body body :headers headers})

    (cond
      ;; Initialize method - always allowed, creates session
      (= method "initialize")
      (let [new-session-id (str (java.util.UUID/randomUUID))]
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Mcp-Session-Id" new-session-id}
         :body {:jsonrpc "2.0"
                :id (:id body)
                :result {:protocolVersion "2025-03-26"
                         :capabilities {}
                         :serverInfo {:name "test-mcp" :version "1.0.0"}}}})

      ;; Missing session when required (only for protected methods)
      (and require-session? (not session-id)
           (not= method "notifications/initialized"))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body {:error "Missing session ID"}}

      ;; Normal methods
      :else
      (let [result (case method
                     "notifications/initialized"
                     {:status 202 :body nil}

                     "tools/list"
                     {:status 200
                      :body {:jsonrpc "2.0"
                             :id (:id body)
                             :result {:tools (let [tools @(:tools @server-state)]
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
                           tools @(:tools @server-state)
                           handler (or (get-in tools [tool-name :handler])
                                       (get-in tools [(keyword tool-name) :handler]))]
                       {:status 200
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
                      :body {:jsonrpc "2.0"
                             :id (:id body)
                             :error {:code -32601
                                     :message (str "Method not found: " method)}}})]
        (update result :body json/generate-string)))))

(defn handler
  "HTTP handler for MCP server"
  [request]
  (if (= :post (:request-method request))
    (let [resp (handle-mcp-request request)]
      (if (string? (:body resp))
        resp
        (update resp :body json/generate-string)))
    {:status 405
     :body "Method not allowed"}))

(defn start-server
  "Start test MCP server on random port.
   Returns map with :port, :stop function, :received-requests atom"
  [& {:keys [tools require-session]}]
  (let [received-reqs (atom [])
        srv (http/run-server (fn [req] (handler req)) {:port 0})
        port (:local-port (meta srv))]
    (reset! server-state {:server srv
                          :port port
                          :received-requests received-reqs
                          :tools (atom (or tools {}))
                          :require-session require-session})
    {:port port
     :stop srv
     :received-requests received-reqs
     :set-tools! (fn [new-tools] (reset! (:tools @server-state) new-tools))}))

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
  []
  (start-server :tools (default-stripe-tools)))
