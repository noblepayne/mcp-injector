(ns mcp-injector.mcp-client
  "MCP client with support for multiple transports (HTTP, STDIO)."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [mcp-injector.mcp-client-stdio :as stdio]))

(def ^:private http-sessions (atom {})) ;; url -> session-id
(def ^:private tool-cache (atom {})) ;; server-id -> [tools]

(def ^:const PROTOCOL_VERSION "2025-06-18")

(def http-client (http/client {:version :http1.1}))

(defn- log-request
  ([level message data]
   (log-request level message data nil))
  ([level message data context]
   (println (json/generate-string
             (merge {:timestamp (str (java.time.Instant/now))
                     :level level
                     :message message
                     :request-id (or (try @(resolve 'mcp-injector.core/*request-id*) (catch Exception _ nil)) "none")}
                    context
                    {:data data})))))

(defn- build-headers
  "Merge user-defined headers from server-config with mandatory protocol headers.
   User headers take precedence over defaults.
   Handles both keyword keys (from EDN) and string keys."
  [server-config & extra-headers]
  (let [base {"Content-Type" "application/json"
              "Accept" "application/json, text/event-stream"
              "MCP-Protocol-Version" PROTOCOL_VERSION}
        normalize (fn [m]
                    (into {} (map (fn [[k v]]
                                    [(if (keyword? k) (name k) k) v]) m)))
        user-headers (or (:headers server-config) {})
        normalized-user (normalize user-headers)]
    (merge base normalized-user (apply merge extra-headers))))

(defn- initialize-http-session! [server-url server-config]
  (try
    (let [init-body {:jsonrpc "2.0" :id "init" :method "initialize"
                     :params {:protocolVersion PROTOCOL_VERSION
                              :capabilities {}
                              :clientInfo {:name "mcp-injector" :version "1.0.0"}}}
          _ (log-request "debug" "Initializing HTTP MCP session" {:url server-url} {:server server-url})
          init-resp (http/post server-url
                               {:body (json/generate-string init-body)
                                :client http-client
                                :headers (build-headers server-config)})
          status (:status init-resp)]
      (if (= 200 status)
        (let [headers (:headers init-resp)
              _ (log-request "debug" "Received initialize headers" {:headers headers} {:server server-url})
              session-id (or (get headers "mcp-session-id")
                             (get headers :mcp-session-id)
                             (get headers "Mcp-Session-Id")
                             (some (fn [[k v]] (when (= "mcp-session-id" (str/lower-case (name k))) v)) headers))]
          (if session-id
            (do
              (swap! http-sessions assoc server-url session-id)
              (try
                (http/post server-url
                           {:body (json/generate-string {:jsonrpc "2.0" :method "notifications/initialized" :params {}})
                            :client http-client
                            :headers (build-headers server-config {"Mcp-Session-Id" session-id})})
                (catch Exception e
                  (log-request "debug" "Failed to send initialized notification (ignoring)" {:error (.getMessage e)} {:server server-url})))
              session-id)
            (do
              (log-request "debug" "No session ID returned - using stateless mode" {:url server-url} {:server server-url})
              nil)))
        (throw (ex-info "Failed to initialize HTTP MCP session" {:status status :body (:body init-resp)}))))
    (catch Exception e
      (log-request "debug" "Session initialization failed" {:url server-url :error (.getMessage e)} {:server server-url})
      (throw e))))

(defn- get-http-session! [server-url server-config]
  (if-let [sid (get @http-sessions server-url)]
    sid
    (locking http-sessions
      (or (get @http-sessions server-url)
          (initialize-http-session! server-url server-config)))))

(defn- parse-mcp-body [resp]
  (let [ct (or (get-in resp [:headers "content-type"])
               (get-in resp [:headers :content-type])
               "")]
    (if (str/includes? (str/lower-case ct) "text/event-stream")
      (->> (str/split-lines (:body resp))
           (filter #(str/starts-with? % "data: "))
           (keep #(try (json/parse-string (subs % 6) true)
                       (catch Exception _ nil)))
           ;; find the one that's a JSON-RPC response (has :result or :error, not a notification)
           (filter :id)
           first)
      (json/parse-string (:body resp) true))))

(defn- call-http
  ([server-url server-config method params]
   (call-http server-url server-config method params nil))
  ([server-url server-config method params extra-headers]
   (try
     (let [sid (get-http-session! server-url server-config)
           session-header (when sid {"Mcp-Session-Id" sid})
           resp (http/post server-url
                           {:headers (build-headers server-config session-header extra-headers)
                            :body (json/generate-string
                                   {:jsonrpc "2.0"
                                    :id (str (java.util.UUID/randomUUID))
                                    :method method
                                    :params params})
                            :client http-client
                            :throw false})
           status (:status resp)
           body (parse-mcp-body resp)]
       (cond
         (= 200 status) body
         (and sid (#{400 401 404} status))
         (do (swap! http-sessions dissoc server-url)
             (call-http server-url server-config method params extra-headers))
         :else body))
     (catch Exception e
       (let [msg (.getMessage e)]
         (log-request "debug" "HTTP call failed" {:url server-url :method method :error msg} {:server server-url})
         {:error msg})))))

(defn list-tools
  ([server-id server-config] (list-tools server-id server-config nil))
  ([server-id server-config policy]
   (let [url (or (:url server-config)
                 (when (and (string? server-id) (str/starts-with? server-id "http")) server-id))]
     (cond
       (:cmd server-config) (stdio/list-tools server-id server-config policy)
       url (let [resp (call-http url server-config "tools/list" {})]
             (if (:error resp)
               resp
               (get-in resp [:result :tools])))
       :else []))))

(defn call-tool
  ([server-id server-config tool-name arguments] (call-tool server-id server-config tool-name arguments nil nil))
  ([server-id server-config tool-name arguments policy] (call-tool server-id server-config tool-name arguments policy nil))
  ([server-id server-config tool-name arguments policy extra-headers]
   (let [url (or (:url server-config)
                 (when (and (string? server-id) (str/starts-with? server-id "http")) server-id))]
     (log-request "info" "Calling Tool" {:tool tool-name} {:server server-id})
     (cond
       (:cmd server-config) (stdio/call-tool server-id server-config tool-name arguments policy)
       url (let [resp (call-http url server-config "tools/call" {:name tool-name :arguments arguments} extra-headers)]
             (cond
               (:error resp) resp
               (:result resp) (get-in resp [:result :content])
               :else {:error "Unknown tool response format"}))
       :else {:error "No transport configured"}))))

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
   (discover-tools server-id server-config tool-names nil))
  ([server-id server-config tool-names policy]
   (let [cache-key server-id]
     (if-let [cached (get @tool-cache cache-key)]
       (cond
         (nil? tool-names) cached
         (empty? tool-names) []
         :else (filter (fn [tool]
                         (some #(= (keyword (:name tool)) %)
                               (map keyword tool-names)))
                       cached))
       (let [all-tools (list-tools server-id server-config policy)]
         (if (and all-tools (not (:error all-tools)))
           (do
             (swap! tool-cache assoc cache-key (vec all-tools))
             (discover-tools server-id server-config tool-names policy))
           (or all-tools [])))))))

(defn get-tool-schema
  "Get schema for a specific tool from an MCP server with caching"
  ([server-id server-config tool-name] (get-tool-schema server-id server-config tool-name nil))
  ([server-id server-config tool-name policy]
   (let [tools (discover-tools server-id server-config nil policy)
         tool (first (filter #(= tool-name (:name %)) tools))]
     (or tool
         {:error (str "Tool not found: " tool-name)}))))

(defn get-cache-state []
  {:tools @tool-cache
   :http-sessions @http-sessions
   :stdio-sessions (stdio/get-active-sessions)})

(defn warm-up! [mcp-config]
  (let [servers (:servers mcp-config)
        policy (get-in mcp-config [:governance :policy])]
    (log-request "info" "Proactive warm-up started" {:servers (keys servers)})
    (let [results (doall (pmap (fn [[id config]]
                                 (try
                                   (let [tools (discover-tools (name id) config nil policy)]
                                     {:id id :success (not (:error tools))})
                                   (catch Exception e
                                     {:id id :success false :error (.getMessage e)})))
                               servers))
          success-count (count (filter :success results))]
      (log-request "info" "Proactive warm-up complete"
                   {:successful success-count
                    :failed (- (count results) success-count)
                    :details results}
                   {}))))
