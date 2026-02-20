#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.string :as str])

(def tools
  {:retrieve_customer
   {:description "Retrieve a customer from Stripe"
    :inputSchema {:type "object"
                  :properties {:customer_id {:type "string"}}
                  :required ["customer_id"]}
    :handler (fn [args]
               {:id (:customer_id args)
                :email "customer@example.com"
                :name "Test Customer"})}

   :list_charges
   {:description "List charges from Stripe"
    :inputSchema {:type "object"
                  :properties {:customer {:type "string"}
                               :limit {:type "integer"}}}
    :handler (fn [_]
               [{:id "ch_123" :amount 1000 :currency "usd"}])}})

(defn handle-request [req]
  (let [method (or (get-in req [:params :method]) (:method req))
        id (:id req)]
    (cond
      (= method "initialize")
      {:jsonrpc "2.0"
       :id id
       :result {:protocolVersion "2025-03-26"
                :capabilities {}
                :serverInfo {:name "test-stdio-mcp" :version "1.0.0"}}}

      (= method "tools/list")
      {:jsonrpc "2.0"
       :id id
       :result {:tools (mapv (fn [[name spec]]
                               {:name (name name)
                                :description (:description spec)
                                :inputSchema (:inputSchema spec)})
                             tools)}}

      (= method "tools/call")
      (let [tool-name (get-in req [:params :name])
            args (get-in req [:params :arguments])
            tool (get tools (keyword tool-name))]
        {:jsonrpc "2.0"
         :id id
         :result {:content [{:type "text"
                             :text (json/generate-string
                                    (if tool
                                      ((:handler tool) args)
                                      {:error (str "Tool not found: " tool-name)}))}]}})
      :else nil)))

(defn -main []
  (let [reader (java.io.BufferedReader. *in*)]
    (loop []
      (let [line (.readLine reader)]
        (when line
          (when (seq (str/trim line))
            (let [req (json/parse-string line true)
                  resp (handle-request req)]
              (when resp
                (println (json/generate-string resp))
                (flush))))
          (recur))))))

(-main)
