(ns mcp-injector.mcp-client-stdio
  "STDIO transport for MCP client.
   Spawns a subprocess and communicates via newline-delimited JSON-RPC 2.0 on stdin/stdout."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.core.async :as async]
            [mcp-injector.policy :as policy])
  (:import (java.io BufferedReader PrintWriter InputStreamReader OutputStreamWriter)))

(def ^:private sessions (atom {})) ;; server-id -> session-state

(defn- log-request [level message data]
  (println (json/generate-string
            {:timestamp (str (java.time.Instant/now))
             :level level
             :message message
             :data data})))

(defn- handle-inbound-request [server-id req writer policy]
  (let [method (:method req)
        id (:id req)]
    (cond
      (= method "sampling/createMessage")
      (if (policy/allow-sampling? policy server-id)
        (do
          (log-request "info" "Sampling request received" {:server server-id :method method})
          ;; TODO: Implement actual LLM routing for sampling
          (.println ^PrintWriter writer (json/generate-string
                                         {:jsonrpc "2.0"
                                          :id id
                                          :error {:code -32601 :message "Sampling implementation pending"}})))
        (do
          (log-request "warn" "Sampling request blocked by policy" {:server server-id})
          (.println ^PrintWriter writer (json/generate-string
                                         {:jsonrpc "2.0"
                                          :id id
                                          :error {:code -32603 :message "Sampling denied by policy"}}))))

      :else
      (do
        (log-request "debug" "Unknown inbound request" {:server server-id :method method})
        (.println ^PrintWriter writer (json/generate-string
                                       {:jsonrpc "2.0"
                                        :id id
                                        :error {:code -32601 :message "Method not found"}}))))))

(defn- reader-loop [server-id in-reader writer pending-requests running policy]
  (try
    (loop []
      (when @running
        (let [line (.readLine in-reader)]
          (if line
            (do
              (when (seq (str/trim line))
                (let [msg (try (json/parse-string line true)
                               (catch Exception _
                                 (log-request "warn" "Failed to parse JSON-RPC line" {:server server-id :line line})
                                 nil))
                      id (:id msg)
                      method (:method msg)]
                  (when msg
                    (cond
                      ;; 1. It's a Response to a client request
                      (and id (or (contains? msg :result) (contains? msg :error)))
                      (when-let [resp-ch (get @pending-requests id)]
                        (async/>!! resp-ch msg)
                        (swap! pending-requests dissoc id))

                      ;; 2. It's an Inbound Request from the server
                      (and id method)
                      (handle-inbound-request server-id msg writer policy)

                      ;; 3. It's an Inbound Notification
                      method
                      (log-request "debug" "Server notification received" {:server server-id :method method})

                      :else
                      (log-request "warn" "Malformed JSON-RPC message" {:server server-id :msg msg})))))
              (recur))
            (reset! running false)))))  ; EOF - silent exit
    (catch java.io.IOException e
      ;; Stream closed is expected during shutdown - be silent
      (when (and @running (not (str/includes? (.getMessage e) "Stream closed")))
        (log-request "error" "MCP Stdio Reader error" {:server server-id :error (.getMessage e)})))
    (catch Exception e
      (when @running
        (log-request "error" "MCP Stdio Reader error" {:server server-id :error (.getMessage e)})))))

(defn- start-process [server-id cmd env cwd policy]
  (let [p (process/process
           cmd
           (merge
            {:in :pipe :out :pipe :err :pipe}
            (when env {:env (into {} (map (fn [[k v]] [(name k) (str v)]) env))})
            (when cwd {:dir (str cwd)})))
        out-writer (PrintWriter. (OutputStreamWriter. (:in p)) true)
        in-reader (BufferedReader. (InputStreamReader. (:out p)))
        err-reader (BufferedReader. (InputStreamReader. (:err p)))
        pending-requests (atom {})
        running (atom true)
        ;; Drain stderr to prevent blocking
        stderr-drain-thread (doto (Thread. #(let [reader err-reader]
                                              (while @running
                                                (try
                                                  (let [line (.readLine reader)]
                                                    (if line
                                                      (when (seq (str/trim line))
                                                        (log-request "debug" "STDIO stderr" {:server server-id :line line}))
                                                      (reset! running false)))  ; EOF - exit loop
                                                  (catch java.io.IOException e
                                                    ;; Stream closed is expected during shutdown - be silent
                                                    (when (and @running (not (str/includes? (.getMessage e) "Stream closed")))
                                                      (log-request "error" "STDIO drain error" {:server server-id :error (.getMessage e)})))
                                                  (catch Exception e
                                                    (when @running
                                                      (log-request "error" "STDIO drain error" {:server server-id :error (.getMessage e)})))))))
                              (.setDaemon true))
        reader-thread (doto (Thread. #(reader-loop server-id in-reader out-writer pending-requests running policy))
                        (.setDaemon true))]
    (.start stderr-drain-thread)
    (.start reader-thread)
    {:process p
     :writer out-writer
     :reader in-reader
     :err-reader err-reader
     :pending pending-requests
     :running running
     :request-id (atom 0)
     :threads [stderr-drain-thread reader-thread]}))

(defn- send-request [session method params]
  (let [id (str (swap! (:request-id session) inc))
        req {:jsonrpc "2.0"
             :id id
             :method method
             :params params}
        resp-ch (async/chan 1)]
    (swap! (:pending session) assoc id resp-ch)
    (.println ^PrintWriter (:writer session) (json/generate-string req))
    (let [[resp _] (async/alts!! [resp-ch (async/timeout 5000)])]
      (swap! (:pending session) dissoc id)
      (if (nil? resp)
        {:error "Request timed out"}
        resp))))

(defn get-session [server-id server-config policy]
  (if-let [session (get @sessions server-id)]
    (if @(:running session)
      session
      (do (swap! sessions dissoc server-id)
          (get-session server-id server-config policy)))
    (let [cmd (:cmd server-config)
          env (:env server-config)
          cwd (:cwd server-config)
          session (start-process server-id cmd env cwd policy)
          ;; Initialize
          init-resp (send-request session "initialize"
                                  {:protocolVersion "2025-03-26"
                                   ;; NOTE: Sampling advertised but disabled until full LLM routing is implemented.
                                   :capabilities {}
                                   :clientInfo {:name "mcp-injector" :version "1.0.0"}})
          ;; Send initialized notification (no ID per spec)
          _ (.println ^PrintWriter (:writer session) (json/generate-string {:jsonrpc "2.0" :method "notifications/initialized" :params {}}))]
      (if (and (map? init-resp) (:error init-resp))
        init-resp
        (do
          (swap! sessions assoc server-id session)
          session)))))

(defn list-tools [server-id server-config policy]
  (let [session (get-session server-id server-config policy)]
    (if (and (map? session) (:error session))
      session
      (let [resp (send-request session "tools/list" {})]
        (if-let [tools (get-in resp [:result :tools])]
          (mapv (fn [t] (update t :name #(some-> % name))) tools)
          (or (:error resp) []))))))

(defn call-tool [server-id server-config tool-name arguments policy]
  (let [session (get-session server-id server-config policy)]
    (if (and (map? session) (:error session))
      session
      (let [resp (send-request session "tools/call"
                               {:name tool-name
                                :arguments arguments})]
        (if-let [result (:result resp)]
          result
          (or (:error resp) {:error "Tool execution failed"}))))))

(defn stop-all []
  (doseq [[_id session] @sessions]
    (reset! (:running session) false)

    ;; Close streams to unblock reader threads
    (when-let [writer (:writer session)]
      (try (.close ^PrintWriter writer) (catch Exception _)))
    (when-let [reader (:reader session)]
      (try (.close ^BufferedReader reader) (catch Exception _)))
    (when-let [err-reader (:err-reader session)]
      (try (.close ^BufferedReader err-reader) (catch Exception _)))

    ;; Destroy process
    (process/destroy-tree (:process session))

    ;; Join threads with timeout
    (doseq [thread (:threads session)]
      (when thread
        (try
          (.join ^Thread thread 2000)  ; Wait up to 2 seconds
          (catch InterruptedException _ nil)
          (catch Exception _ nil)))))
  (reset! sessions {}))

(defn get-active-sessions []
  (into {} (map (fn [[id session]]
                  [id {:pid (:pid (:process session))
                       :running @(:running session)
                       :pending-count (count @(:pending session))}])
                @sessions)))
