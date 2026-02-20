(ns mcp-injector.mcp-client
  "MCP client with support for multiple transports (HTTP, STDIO)."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [mcp-injector.mcp-client-stdio :as stdio]))

(def ^:private http-sessions (atom {})) ;; url -> session-id
(def ^:private tool-cache (atom {})) ;; server-id -> [tools]

(def ^:const PROTOCOL_VERSION "2025-03-26")

(defn- log-debug [msg data]
  (println (json/generate-string {:timestamp (str (java.time.Instant/now))
                                  :level "debug"
                                  :message msg
                                  :data data})))

(defn- initialize-http-session! [server-url]
  (let [init-body {:jsonrpc "2.0" :id "init" :method "initialize"
                   :params {:protocolVersion PROTOCOL_VERSION
                            :capabilities {}
                            :clientInfo {:name "mcp-injector" :version "1.0.0"}}}
        _ (log-debug "Initializing HTTP MCP session" {:url server-url})
        init-resp (http/post server-url
                             {:body (json/generate-string init-body)
                              :headers {"Content-Type" "application/json"
                                        "Accept" "application/json"
                                        "MCP-Protocol-Version" PROTOCOL_VERSION}})
        status (:status init-resp)]
    (if (= 200 status)
      (let [headers (:headers init-resp)
            _ (log-debug "Received initialize headers" {:headers headers})
            session-id (or (get headers "mcp-session-id")
                           (get headers :mcp-session-id)
                           (get headers "Mcp-Session-Id")
                           (some (fn [[k v]] (when (= "mcp-session-id" (str/lower-case (name k))) v)) headers))]
        (if session-id
          (do
            (swap! http-sessions assoc server-url session-id)
            ;; Send initialized notification (no ID per spec)
            (try
              (http/post server-url
                         {:body (json/generate-string {:jsonrpc "2.0" :method "notifications/initialized" :params {}})
                          :headers {"Mcp-Session-Id" session-id
                                    "Content-Type" "application/json"
                                    "Accept" "application/json"
                                    "MCP-Protocol-Version" PROTOCOL_VERSION}})
              (catch Exception e
                (log-debug "Failed to send initialized notification (ignoring)" {:error (.getMessage e)})))
            session-id)
          (throw (ex-info "No Mcp-Session-Id header in initialize response" {:headers headers}))))
      (throw (ex-info "Failed to initialize HTTP MCP session" {:status status :body (:body init-resp)})))))

(defn- get-http-session! [server-url]
  (or (get @http-sessions server-url)
      (initialize-http-session! server-url)))

(defn- call-http [server-url method params]
  (try
    (let [sid (get-http-session! server-url)
          resp (http/post server-url
                          {:headers {"Mcp-Session-Id" sid
                                     "Content-Type" "application/json"
                                     "Accept" "application/json"
                                     "MCP-Protocol-Version" PROTOCOL_VERSION}
                           :body (json/generate-string
                                  {:jsonrpc "2.0"
                                   :id (str (java.util.UUID/randomUUID))
                                   :method method
                                   :params params})
                           :throw false})
          status (:status resp)
          body (json/parse-string (:body resp) true)]
      (cond
        (= 200 status) body
        (and sid (#{400 401 404} status))
        (do (swap! http-sessions dissoc server-url)
            (call-http server-url method params))
        :else body))
    (catch Exception e
      {:error (.getMessage e)})))

(defn list-tools [server-id server-config]
  (let [url (or (:url server-config)
                (when (and (string? server-id) (str/starts-with? server-id "http")) server-id))]
    (cond
      (:cmd server-config) (stdio/list-tools server-id server-config)
      url (let [resp (call-http url "tools/list" {})]
            (if (:error resp)
              resp
              (get-in resp [:result :tools])))
      :else [])))

(defn call-tool [server-id server-config tool-name arguments]
  (let [url (or (:url server-config)
                (when (and (string? server-id) (str/starts-with? server-id "http")) server-id))]
    (cond
      (:cmd server-config) (stdio/call-tool server-id server-config tool-name arguments)
      url (let [resp (call-http url "tools/call" {:name tool-name :arguments arguments})]
            (cond
              (:error resp) resp
              (:result resp) (get-in resp [:result :content])
              :else {:error "Unknown tool response format"}))
      :else {:error "No transport configured"})))

(defn clear-tool-cache! []
  (reset! tool-cache {})
  (reset! http-sessions {})
  (stdio/stop-all))

(defn discover-tools
  ([server-id server-config-or-tools]
   (if (or (nil? server-config-or-tools) (map? server-config-or-tools))
     (discover-tools server-id server-config-or-tools nil)
     (discover-tools server-id nil server-config-or-tools)))
  ([server-id server-config tool-names]
   (let [cache-key server-id]
     (if-let [cached (get @tool-cache cache-key)]
       (cond
         (nil? tool-names) cached
         (empty? tool-names) []
         :else (filter (fn [tool]
                         (some #(= (keyword (:name tool)) %)
                               (map keyword tool-names)))
                       cached))
       (let [all-tools (list-tools server-id server-config)]
         (if (and all-tools (not (:error all-tools)))
           (do
             (swap! tool-cache assoc cache-key (vec all-tools))
             (discover-tools server-id server-config tool-names))
           (or all-tools [])))))))

(defn get-tool-schema
  "Get schema for a specific tool from an MCP server with caching"
  [server-id server-config tool-name]
  (let [tools (discover-tools server-id server-config nil)
        tool (first (filter #(= tool-name (:name %)) tools))]
    (or tool
        {:error (str "Tool not found: " tool-name)})))
