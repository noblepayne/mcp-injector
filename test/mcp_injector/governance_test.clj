(ns mcp-injector.governance-test
  "Tests for governance configuration: pii and audit enabled/disabled"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(def test-state (atom {}))

(defn integration-fixture [test-fn]
  (let [llm (test-llm/start-server)
        mcp (test-mcp/start-test-mcp-server)
        audit-file (io/file "logs/test-audit.log.ndjson")
        _ (io/make-parents audit-file)
        injector (core/start-server
                  {:port 0
                   :host "127.0.0.1"
                   :llm-url (str "http://localhost:" (:port llm))
                   :mcp-servers {:servers
                                 {:stripe
                                  {:url (str "http://localhost:" (:port mcp))
                                   :tools ["retrieve_customer"]
                                   :trust :restore}}
                                 :llm-gateway {:url (str "http://localhost:" (:port llm))
                                               :governance {:pii {:enabled true}
                                                            :audit {:enabled true
                                                                    :path "logs/test-audit.log.ndjson"}
                                                            :policy {:mode :permissive}}}}
                   :audit-log-path "logs/test-audit.log.ndjson"})]
    (swap! test-state assoc :llm llm :mcp mcp :injector injector :audit-file audit-file)
    (try
      (test-fn)
      (finally
        (core/stop-server injector)
        (test-llm/stop-server llm)
        (test-mcp/stop-server mcp)
        (when (.exists audit-file) (.delete audit-file))))))

(use-fixtures :once integration-fixture)

(deftest test-pii-enabled-default
  (let [{:keys [injector llm]} @test-state
        port (:port injector)]
    (test-llm/set-next-response llm {:role "assistant" :content "Got it" :tool_calls nil})
    (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "test"
                                        :messages [{:role "user" :content "user: admin@example.com"}]})
                                :headers {"Content-Type" "application/json"}})
          llm-req (last (:received-requests llm))
          llm-msg (json/parse-string llm-req true)
          user-msg (-> llm-msg :messages last :content)]
      (is (= 200 (:status response)))
      (is (str/includes? user-msg "EMAIL_ADDRESS")
          "Email should be redacted when PII enabled (default)"))))

(deftest test-pii-disabled-via-governance
  (let [{:keys [injector llm mcp]} @test-state
        port (:port injector)]
    (core/stop-server injector)
    (let [injector (core/start-server
                    {:port 0
                     :host "127.0.0.1"
                     :llm-url (str "http://localhost:" (:port llm))
                     :mcp-servers {:servers
                                   {:stripe
                                    {:url (str "http://localhost:" (:port mcp))
                                     :tools ["retrieve_customer"]
                                     :trust :restore}}
                                   :llm-gateway {:url (str "http://localhost:" (:port llm))
                                                 :governance {:pii {:enabled false}
                                                              :audit {:enabled false}
                                                              :policy {:mode :permissive}}}}
                     :audit-log-path "logs/test-audit-disabled.log.ndjson"})]
      (swap! test-state assoc :injector injector)
      (try
        (test-llm/set-next-response llm {:role "assistant" :content "Got it" :tool_calls nil})
        (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                   {:body (json/generate-string
                                           {:model "test"
                                            :messages [{:role "user" :content "user: admin@example.com"}]})
                                    :headers {"Content-Type" "application/json"}})
              llm-req (last (:received-requests llm))
              llm-msg (json/parse-string llm-req true)
              user-msg (-> llm-msg :messages last :content)]
          (is (= 200 (:status response)))
          (is (str/includes? user-msg "admin@example.com")
              "Email should NOT be redacted when PII disabled"))
        (finally
          (core/stop-server injector))))))

(deftest test-audit-enabled-default
  (let [{:keys [injector llm]} @test-state
        port (:port injector)
        test-audit-file (io/file "logs/test-audit.log.ndjson")]
    (test-llm/set-next-response llm {:role "assistant" :content "Audit test" :tool_calls nil})
    (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "test"
                                        :messages [{:role "user" :content "test"}]})
                                :headers {"Content-Type" "application/json"}})]
      (is (= 200 (:status response)))
      (is (.exists test-audit-file)
          "Audit file should exist when audit enabled"))))

(deftest test-trust-none-default
  (let [{:keys [injector llm]} @test-state
        port (:port injector)]
    (test-llm/set-next-response llm {:role "assistant" :content "Done" :tool_calls nil})
    (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "test"
                                        :messages [{:role "user" :content "find user admin@example.com"}]})
                                :headers {"Content-Type" "application/json"}})]
      (is (= 200 (:status response))))))
