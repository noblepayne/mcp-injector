(ns mcp-injector.mcp-client
  "HTTP client for calling MCP servers."
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]))

(defn- build-request-body
  "Build JSON-RPC request body"
  [method params id]
  {:jsonrpc "2.0"
   :method method
   :params params
   :id id})

(defn list-tools
  "List available tools from an MCP server"
  [server-url]
  (let [body (build-request-body "tools/list" {} "list-req")
        response @(http/post server-url
                             {:body (json/generate-string body)
                              :headers {"Content-Type" "application/json"}})]
    (if (= 200 (:status response))
      (let [parsed (json/parse-string (:body response) true)]
        (get-in parsed [:result :tools] []))
      (throw (ex-info "Failed to list tools"
                      {:status (:status response)
                       :body (:body response)})))))

(defn call-tool
  "Call a tool on an MCP server"
  [server-url tool-name arguments]
  (let [body (build-request-body "tools/call"
                                 {:name tool-name
                                  :arguments arguments}
                                 (str "call-" (java.util.UUID/randomUUID)))
        response @(http/post server-url
                             {:body (json/generate-string body)
                              :headers {"Content-Type" "application/json"}})]
    (if (= 200 (:status response))
      (let [parsed (json/parse-string (:body response) true)]
        (if (:error parsed)
          {:error (:error parsed)}
          (get-in parsed [:result :content])))
      {:error {:message (str "HTTP error: " (:status response))
               :status (:status response)}})))

(defn get-tool-schema
  "Get schema for a specific tool from an MCP server"
  [server-url tool-name]
  ;; Phase 1: Get full tool list and filter
  ;; Phase 2: Cache schemas per request
  (let [tools (list-tools server-url)
        tool (first (filter #(= tool-name (:name %)) tools))]
    (or tool
        {:error (str "Tool not found: " tool-name)})))
