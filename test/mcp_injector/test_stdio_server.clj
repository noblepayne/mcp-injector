(ns mcp-injector.test-stdio-server
  "Test infrastructure for spawning MCP stdio servers."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.core.async :as async]
            [babashka.process :as process])
  (:import (java.io BufferedReader PrintWriter InputStreamReader OutputStreamWriter)))

(defn- reader-loop [in-reader pending-requests running]
  (try
    (loop []
      (when @running
        (let [line (.readLine in-reader)]
          (if line
            (do
              (when (seq (str/trim line))
                (let [resp (try (json/parse-string line true)
                                (catch Exception _
                                  nil))
                      rid (:id resp)]
                  (when resp
                    (if (and rid (get @pending-requests rid))
                      (do
                        (async/>!! (get @pending-requests rid) resp)
                        (swap! pending-requests dissoc rid))
                      (println "Unexpected response:" resp)))))
              (recur))
            (reset! running false)))))  ; EOF - silent exit
    (catch java.io.IOException e
      ;; Stream closed is expected during shutdown - be silent
      (when (and @running (not (str/includes? (.getMessage e) "Stream closed")))
        (println "Stdio reader error:" (.getMessage e))))
    (catch Exception e
      (when @running
        (println "Stdio reader error:" (.getMessage e))))))

(defn start-server
  [& {:keys [cmd env cwd]}]
  (let [p (process/process
           cmd
           (merge
            {:in :pipe
             :out :pipe
             :err :inherit}
            (when env {:env env})
            (when cwd {:dir cwd})))
        out-writer (PrintWriter. (OutputStreamWriter. (:in p)) true)
        in-reader (BufferedReader. (InputStreamReader. (:out p)))
        request-id (atom 0)
        pending-requests (atom {})
        running (atom true)
        reader-thread (doto (Thread. #(reader-loop in-reader pending-requests running))
                        (.setDaemon true))]

    (.start reader-thread)

    {:process p
     :thread reader-thread
     :writer out-writer
     :reader in-reader
     :running running
     :stop (fn []
             (reset! running false)

             ;; Close streams to unblock reader thread
             (try (.close ^PrintWriter out-writer) (catch Exception _))
             (try (.close ^BufferedReader in-reader) (catch Exception _))

             ;; Destroy process
             (process/destroy-tree p)

             ;; Join reader thread with timeout
             (try
               (.join ^Thread reader-thread 2000)
               (catch InterruptedException _ nil)
               (catch Exception _ nil)))
     :send (fn [method params]
             (let [id (str (swap! request-id inc))
                   req {:jsonrpc "2.0"
                        :id id
                        :method method
                        :params params}
                   resp-ch (async/chan 1)]
               (swap! pending-requests assoc id resp-ch)
               (.println out-writer (json/generate-string req))
               (let [resp (async/<!! resp-ch)]
                 (swap! pending-requests dissoc id)
                 resp)))}))

(defn stop-server [srv]
  (when srv
    ((:stop srv))))

(defn start-test-server []
  (start-server :cmd "bb test/mcp_injector/test_stdio_mcp.clj"))
