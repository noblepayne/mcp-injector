(ns mcp-injector.test-mcp-server
  "Real http-kit MCP server for integration testing.
   Implements enough of the MCP protocol to test mcp-injector."
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]))

(def ^:private server-state (atom nil))

(defn- handle-mcp-request
  "Handle incoming MCP JSON-RPC request"
  [request]
  (let [body (json/parse-string (slurp (:body request)) true)
        method (get-in body [:params :method] (:method body))]
    (swap! (:received-requests @server-state) conj body)

    (case method
      "tools/list"
      {:jsonrpc "2.0"
       :id (:id body)
       :result {:tools (->> @(:tools @server-state)
                            (map (fn [[name schema]]
                                   {:name name
                                    :description (:description schema)
                                    :inputSchema (:schema schema)})))}}

      "tools/call"
      (let [tool-name (get-in body [:params :name])
            args (get-in body [:params :arguments])
            handler (get-in @(:tools @server-state) [tool-name :handler])]
        {:jsonrpc "2.0"
         :id (:id body)
         :result (if handler
                   {:content [{:type "text"
                               :text (json/generate-string (handler args))}]}
                   {:content [{:type "text"
                               :text (json/generate-string {:error "Tool not found"})}]
                    :isError true})})

      ;; Unknown method
      {:jsonrpc "2.0"
       :id (:id body)
       :error {:code -32601
               :message (str "Method not found: " method)}})))

(defn handler
  "HTTP handler for MCP server"
  [request]
  (if (= :post (:request-method request))
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string (handle-mcp-request request))}
    {:status 405
     :body "Method not allowed"}))

(defn start-server
  "Start test MCP server on random port.
   Returns map with :port, :stop function, :received-requests atom"
  [& {:keys [tools]}]
  (let [received-reqs (atom [])
        srv (http/run-server handler {:port 0})
        port (:local-port (meta srv))]
    (reset! server-state {:server srv
                          :port port
                          :received-requests received-reqs
                          :tools (atom (or tools {}))})
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
