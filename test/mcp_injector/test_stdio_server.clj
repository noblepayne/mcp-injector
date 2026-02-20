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
                (let [resp (json/parse-string line true)
                      rid (:id resp)]
                  (if (and rid (get @pending-requests rid))
                    (do
                      (async/>!! (get @pending-requests rid) resp)
                      (swap! pending-requests dissoc rid))
                    (println "Unexpected response:" resp))))
              (recur))
            (do (println "Stdio reader: EOF") (reset! running false))))))
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
        reader-thread (Thread. #(reader-loop in-reader pending-requests running))]

    (.start reader-thread)

    {:process p
     :thread reader-thread
     :running running
     :stop (fn []
             (reset! running false)
             (process/destroy p))
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
