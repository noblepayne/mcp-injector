(ns mcp-injector.mcp-client
  "HTTP client for calling MCP servers.
   Implements Streamable HTTP transport with session management."
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private sessions (atom {})) ;; url -> session-id
(def ^:const PROTOCOL_VERSION "2025-03-26")

(defn- build-request-body
  "Build JSON-RPC request body"
  [method params id]
  {:jsonrpc "2.0"
   :method method
   :params params
   :id id})

(defn- get-headers [session-id]
  (cond-> {"Content-Type" "application/json"
           "Accept" "application/json"
           "MCP-Protocol-Version" PROTOCOL_VERSION}
    session-id (assoc "Mcp-Session-Id" session-id)))

(defn clear-sessions! []
  (reset! sessions {}))

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(defn- log-debug [msg data]
  (println (json/generate-string {:timestamp (str (java.time.Instant/now))
                                  :level "debug"
                                  :message msg
                                  :data data})))

(defn- initialize-session! [server-url]
  (let [init-body (build-request-body "initialize"
                                      {:protocolVersion PROTOCOL_VERSION
                                       :capabilities {}
                                       :clientInfo {:name "mcp-injector" :version "1.0.0"}}
                                      "init")
        _ (log-debug "Initializing MCP session" {:url server-url})
        init-resp @(http/post server-url
                              {:body (json/generate-string init-body)
                               :headers (get-headers nil)})
        status (:status init-resp)
        body-str (some-> init-resp :body body->string)]
    (if (= 200 status)
      (let [headers (:headers init-resp)
            ;; Case-insensitive search for mcp-session-id
            session-id (some (fn [[k v]]
                               (when (= "mcp-session-id" (str/lower-case (name k)))
                                 v))
                             headers)
            _ (log-debug "MCP session initialized" {:url server-url :session-id session-id})
            initialized-body (build-request-body "notifications/initialized" {} nil)]
        ;; Send initialized notification
        @(http/post server-url
                    {:body (json/generate-string initialized-body)
                     :headers (get-headers session-id)})
        (when session-id
          (swap! sessions assoc server-url session-id))
        session-id)
      (throw (ex-info "Failed to initialize MCP session"
                      {:status status
                       :body body-str})))))

(defn- get-or-create-session! [server-url]
  (or (get @sessions server-url)
      (initialize-session! server-url)))

(defn list-tools
  "List available tools from an MCP server"
  [server-url]
  (let [session-id (get-or-create-session! server-url)
        _ (log-debug "Calling tools/list" {:url server-url :session-id session-id})
        body (build-request-body "tools/list" {} "list-req")
        response @(http/post server-url
                             {:body (json/generate-string body)
                              :headers (get-headers session-id)})
        status (:status response)
        body-str (some-> response :body body->string)]
    (cond
      (= 200 status)
      (let [parsed (json/parse-string body-str true)]
        (log-debug "tools/list success" {:url server-url :tool-count (count (get-in parsed [:result :tools]))})
        (get-in parsed [:result :tools] []))

      ;; Handle session expiration
      (#{400 404} status)
      (do
        (swap! sessions dissoc server-url)
        (let [_new-session-id (get-or-create-session! server-url)]
          (list-tools server-url)))

      :else
      (throw (ex-info "Failed to list tools"
                      {:status status
                       :body body-str})))))

(defn call-tool
  "Call a tool on an MCP server"
  [server-url tool-name arguments]
  (let [session-id (get-or-create-session! server-url)
        _ (log-debug "Calling tools/call" {:url server-url :tool tool-name :session-id session-id})
        body (build-request-body "tools/call"
                                 {:name tool-name
                                  :arguments arguments}
                                 (str "call-" (java.util.UUID/randomUUID)))
        response @(http/post server-url
                             {:body (json/generate-string body)
                              :headers (get-headers session-id)})
        status (:status response)
        body-str (some-> response :body body->string)]
    (cond
      (= 200 status)
      (let [parsed (json/parse-string body-str true)]
        (log-debug "tools/call response" {:url server-url :tool tool-name :success (not (:error parsed))})
        (if (:error parsed)
          {:error (:error parsed)}
          (get-in parsed [:result :content])))

      ;; Handle session expiration
      (#{400 404} status)
      (do
        (swap! sessions dissoc server-url)
        (call-tool server-url tool-name arguments))

      :else
      {:error {:message (str "HTTP error: " status)
               :status status
               :body body-str}})))

(def ^:private schema-cache (atom {}))

(defn clear-schema-cache! []
  (reset! schema-cache {}))

(defn get-tool-schema
  "Get schema for a specific tool from an MCP server with caching"
  [server-url tool-name]
  (let [cache-key [server-url tool-name]]
    (if-let [cached (get @schema-cache cache-key)]
      cached
      (let [tools (list-tools server-url)
            tool (first (filter #(= tool-name (:name %)) tools))]
        (when tool
          (swap! schema-cache assoc cache-key tool))
        (or tool
            {:error (str "Tool not found: " tool-name)})))))

(def ^:private tool-cache (atom {})) ;; server-url -> [tools]

(defn clear-tool-cache! []
  (reset! tool-cache {}))

(defn discover-tools
  "Discover tools from an MCP server with caching and optional filtering.
   - If tool-names is nil, returns all discovered tools
   - If tool-names is empty, returns empty vector
   - If tool-names is a vector, returns only matching tools"
  [server-url tool-names]
  (let [cache-key server-url]
    (if-let [cached (get @tool-cache cache-key)]
      (cond
        (nil? tool-names) cached
        (empty? tool-names) []
        :else (filter (fn [tool]
                       (some #(= (keyword (:name tool)) %)
                             (map keyword tool-names)))
                     cached))
      (let [all-tools (list-tools server-url)]
        (swap! tool-cache assoc cache-key all-tools)
        (discover-tools server-url tool-names)))))

(defn discover-all-tools
  "Discover all tools from an MCP server without filtering (alias for discover-tools with nil)"
  [server-url]
  (discover-tools server-url nil))
