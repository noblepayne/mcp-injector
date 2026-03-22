(ns mcp-injector.stress-test
  "Concurrency and race condition tests for mcp-injector."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [mcp-injector.core :as core]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.test-mcp-server :as test-mcp]))

(def ^:dynamic *injector* nil)
(def ^:dynamic *test-llm* nil)
(def ^:dynamic *test-mcp* nil)

(use-fixtures :once
  (fn [t]
    (let [llm (test-llm/start-server)
          mcp (test-mcp/start-test-mcp-server)
          injector (core/start-server
                    {:port 0
                     :host "127.0.0.1"
                     :llm-url (str "http://localhost:" (:port llm))
                     :mcp-config-path "mcp-servers.example.edn"
                     :log-level :warn})]
      (binding [*injector* injector
                *test-llm* llm
                *test-mcp* mcp]
        (t)
        (core/stop-server injector)
        (test-llm/stop-server llm)
        (test-mcp/stop-server mcp)))))

(deftest pmap-load-test
  (testing "50 concurrent completions under pmap load"
    (test-llm/clear-responses *test-llm*)
    (dotimes [_ 100]
      (test-llm/set-next-response *test-llm* {:role "assistant" :content "Stress test response"}))
    (let [n 50
          urls (repeat n (str "http://localhost:" (:port *injector*) "/v1/chat/completions"))
          requests (pmap (fn [url]
                           @(http/post url
                                       {:body (json/generate-string
                                               {:model "test"
                                                :messages [{:role "user" :content "ping"}]})
                                        :headers {"Content-Type" "application/json"}}))
                         urls)]
      (is (= n (count requests)))
      (is (every? #(= 200 (:status %)) requests))
      (is (every? #(str/includes? (str (:body %)) "Stress test response") requests)))))

(deftest atomic-stats-integrity-test
  (testing "Trace headers and turn counts remain accurate under concurrency"
    (test-llm/clear-responses *test-llm*)
    (dotimes [_ 40]
      (test-llm/set-next-response *test-llm* {:role "assistant" :content "Stats test"}))
    (let [n 20
          url (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
          results (pmap (fn [_]
                          (let [resp @(http/post url
                                                 {:body (json/generate-string
                                                         {:model "test"
                                                          :messages [{:role "user" :content "ping"}]})
                                                  :headers {"Content-Type" "application/json"}})]
                            (:headers resp)))
                        (range n))]
      (is (= n (count results)))
      (is (every? #(some? (get % :x-injector-traceparent)) results))
      ;; Verify traceparents are unique
      (is (= n (count (set (map :x-injector-traceparent results))))))))
