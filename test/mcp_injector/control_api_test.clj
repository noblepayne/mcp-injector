(ns mcp-injector.control-api-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]))

(def ^:private test-port 8099)

(defn- start-test-env [test-run]
  (let [mcp-server (test-mcp/start-test-mcp-server)
        llm-server (test-llm/start-test-llm-server)
        injector (core/start-server
                  {:port test-port
                   :host "127.0.0.1"
                   :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
                                                   :tools ["retrieve_customer"]}}}
                   :llm-url (str "http://localhost:" (:port llm-server))})]
    (try
      (test-run)
      (finally
        (core/stop-server injector)
        (test-mcp/stop-server mcp-server)
        (test-llm/stop-server llm-server)))))

(use-fixtures :once start-test-env)

(deftest test-control-api-status
  (testing "GET /api/v1/status returns ok"
    (let [resp @(http/get (str "http://localhost:" test-port "/api/v1/status"))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (= "ok" (:status body))))))

(deftest test-control-api-mcp-tools
  (testing "GET /api/v1/mcp/tools returns discovered tools"
    ;; Trigger discovery first via a chat call or direct API if we had one
    ;; For now, discovery happens on first chat turn or we can just check if empty but valid
    (let [resp @(http/get (str "http://localhost:" test-port "/api/v1/mcp/tools"))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (contains? body :tools))
      (is (contains? body :http-sessions)))))

(deftest test-control-api-llm-state
  (testing "GET /api/v1/llm/state returns gateway state"
    (let [resp @(http/get (str "http://localhost:" test-port "/api/v1/llm/state"))
          body (json/parse-string (:body resp) true)]
      (is (= 200 (:status resp)))
      (is (contains? body :cooldowns))
      (is (contains? body :usage)))))

(deftest test-legacy-stats-still-works
  (testing "GET /stats is still available"
    (let [resp @(http/get (str "http://localhost:" test-port "/stats"))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "stats")))))
