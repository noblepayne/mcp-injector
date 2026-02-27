(ns mcp-injector.mcp-client-stdio
  "STDIO transport for MCP client.
   Spawns a subprocess and communicates via newline-delimited JSON-RPC 2.0 on stdin/stdout."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.core.async :as async])
  (:import (java.io BufferedReader PrintWriter InputStreamReader OutputStreamWriter)))

(def ^:private sessions (atom {})) ;; server-id -> session-state

(defn- log-request [level message data]
  (println (json/generate-string
            {:timestamp (str (java.time.Instant/now))
             :level level
             :message message
             :data data})))

(defn- reader-loop [server-id in-reader pending-requests running]
  (try
    (loop []
      (when @running
        (let [line (.readLine in-reader)]
          (if line
            (do
              (when (seq (str/trim line))
                (let [resp (json/parse-string line true)
                      rid (:id resp)]
                  (if (and rid (get @pending-requests rid))
                    (do
                      (async/>!! (get @pending-requests rid) resp)
                      (swap! pending-requests dissoc rid))
                    nil)))
              (recur))
            (reset! running false)))))
    (catch Exception e
      (when @running
        (log-request "error" "MCP Stdio Reader error" {:server server-id :error (.getMessage e)})))))

(defn- start-process [server-id cmd env cwd]
  (let [p (process/process
           cmd
           (merge
            {:in :pipe :out :pipe :err :inherit}
            (when env {:env (into {} (map (fn [[k v]] [(name k) (str v)]) env))})
            (when cwd {:dir (str cwd)})))
        out-writer (PrintWriter. (OutputStreamWriter. (:in p)) true)
        in-reader (BufferedReader. (InputStreamReader. (:out p)))
        pending-requests (atom {})
        running (atom true)
        reader-thread (Thread. #(reader-loop server-id in-reader pending-requests running))]
    (.start reader-thread)
    {:process p
     :writer out-writer
     :pending pending-requests
     :running running
     :request-id (atom 0)}))

(defn- send-request [session method params]
  (let [id (str (swap! (:request-id session) inc))
        req {:jsonrpc "2.0"
             :id id
             :method method
             :params params}
        resp-ch (async/chan 1)]
    (swap! (:pending session) assoc id resp-ch)
    (.println ^PrintWriter (:writer session) (json/generate-string req))
    (let [[resp _] (async/alts!! [resp-ch (async/timeout 30000)])]
      (swap! (:pending session) dissoc id)
      (if (nil? resp)
        {:error "Request timed out"}
        resp))))

(defn get-session [server-id server-config]
  (if-let [session (get @sessions server-id)]
    (if @(:running session)
      session
      (do (swap! sessions dissoc server-id)
          (get-session server-id server-config)))
    (let [cmd (:cmd server-config)
          env (:env server-config)
          cwd (:cwd server-config)
          session (start-process server-id cmd env cwd)
          ;; Initialize
          init-resp (send-request session "initialize"
                                  {:protocolVersion "2025-03-26"
                                   :capabilities {}
                                   :clientInfo {:name "mcp-injector" :version "1.0.0"}})
          ;; Send initialized notification (no ID per spec)
          _ (.println ^PrintWriter (:writer session) (json/generate-string {:jsonrpc "2.0" :method "notifications/initialized" :params {}}))]
      (if (:error init-resp)
        init-resp
        (do
          (swap! sessions assoc server-id session)
          session)))))

(defn list-tools [server-id server-config]
  (let [session (get-session server-id server-config)]
    (if (:error session)
      session
      (let [resp (send-request session "tools/list" {})]
        (if-let [tools (get-in resp [:result :tools])]
          tools
          (or (:error resp) []))))))

(defn call-tool [server-id server-config tool-name arguments]
  (let [session (get-session server-id server-config)]
    (if (:error session)
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
    (process/destroy (:process session)))
  (reset! sessions {}))

(defn get-active-sessions []
  (into {} (map (fn [[id session]]
                  [id {:pid (:pid @(:process session))
                       :running @(:running session)
                       :pending-count (count @(:pending session))}])
                @sessions)))
