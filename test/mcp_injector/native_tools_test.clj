(ns mcp-injector.native-tools-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cheshire.core :as json]
            [org.httpkit.client :as http]
            [mcp-injector.core :as core]
            [mcp-injector.test-llm-server :as test-llm]))

(def ^:dynamic *test-llm* nil)
(def ^:dynamic *injector* nil)

(defn native-tools-fixture [test-fn]
  (let [llm-server (test-llm/start-server)
        injector-server (core/start-server {:port 0
                                            :host "127.0.0.1"
                                            :llm-url (str "http://localhost:" (:port llm-server))
                                            :mcp-servers {:servers {}
                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
    (try
      (binding [*test-llm* llm-server
                *injector* injector-server]
        (test-fn))
      (finally
        (core/stop-server injector-server)
        (test-llm/stop-server llm-server)))))

(use-fixtures :once native-tools-fixture)

(defn clear-requests-fixture [test-fn]
  (test-llm/clear-responses *test-llm*)
  (reset! (:received-requests *test-llm*) [])
  (test-fn))

(use-fixtures :each clear-requests-fixture)

(deftest clojure-eval-basic
  (testing "clojure-eval executes simple Clojure code"
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "clojure-eval"
                                       :arguments {:code "(+ 1 2)"}}])

    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "The result is 3"})

    (let [response @(http/post
                     (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                     {:body (json/generate-string
                             {:model "test-model"
                              :messages [{:role "user" :content "What is 1 + 2?"}]
                              :stream false})
                      :headers {"Content-Type" "application/json"}})]
      (is (= 200 (:status response)))

      (let [requests @(:received-requests *test-llm*)]
        (is (= 2 (count requests)))
        (let [second-req (second requests)
              messages (:messages second-req)
              tool-result (last messages)]
          (is (= "tool" (:role tool-result)))
          (is (= "clojure-eval" (:name tool-result)))
          (is (some? (re-find #"3" (:content tool-result)))))))))

(deftest clojure-eval-data-structures
  (testing "clojure-eval returns complex data as pr-str"
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "clojure-eval"
                                       :arguments {:code "(vec (range 5))"}}])

    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Done"})

    (let [response @(http/post
                     (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                     {:body (json/generate-string
                             {:model "test-model"
                              :messages [{:role "user" :content "test"}]
                              :stream false})
                      :headers {"Content-Type" "application/json"}})]
      (is (= 200 (:status response))))))

(deftest clojure-eval-error-handling
  (testing "clojure-eval returns error for invalid code"
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "clojure-eval"
                                       :arguments {:code "(+"}}]) ;; invalid

    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Got error"})

    (let [response @(http/post
                     (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                     {:body (json/generate-string
                             {:model "test-model"
                              :messages [{:role "user" :content "test"}]
                              :stream false})
                      :headers {"Content-Type" "application/json"}})]
      (is (= 200 (:status response))))))

(deftest unknown-tools-fall-through
  (testing "Unknown tools like exec pass through without being caught by our filter"
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "exec"
                                       :arguments {:cmd "ls"}}])

    (let [response @(http/post
                     (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                     {:body (json/generate-string
                             {:model "test-model"
                              :messages [{:role "user" :content "run ls"}]
                              :stream false})
                      :headers {"Content-Type" "application/json"}})]
      (is (= 200 (:status response))))))
