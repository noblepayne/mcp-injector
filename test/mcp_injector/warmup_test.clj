(ns mcp-injector.warmup-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]))

(def ^:private test-port 8101)

(defn- start-test-env [test-run]
  (let [mcp-server (test-mcp/start-test-mcp-server)
        llm-server (test-llm/start-server)
        injector (core/start-server
                  {:port test-port
                   :host "127.0.0.1"
                   :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
                                                    :tools ["retrieve_customer"]}}}
                   :llm-url (str "http://localhost:" (:port llm-server))})]
    (try
      ;; Give it a tiny bit of time for the background future to start/finish
      ;; In a real app we'd check the /status API
      (Thread/sleep 500)
      (test-run)
      (finally
        (core/stop-server injector)
        (test-mcp/stop-server mcp-server)
        (test-llm/stop-server llm-server)))))

(use-fixtures :once start-test-env)

(deftest test-proactive-warmup
  (testing "Tools are discovered automatically on startup without any chat requests"
    (let [resp @(http/get (str "http://localhost:" test-port "/api/v1/mcp/tools"))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      ;; The 'stripe' server should already be in the cache
      (is (contains? (:tools body) :stripe))
      (is (not (empty? (get-in body [:tools :stripe])))))))

(deftest test-warmup-status-flag
  (testing "/api/v1/status shows warming-up state correctly"
    (let [resp @(http/get (str "http://localhost:" test-port "/api/v1/status"))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (contains? body :warming-up?)))))
