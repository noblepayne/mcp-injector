(ns mcp-injector.passthrough-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [babashka.http-client :as http]
            [mcp-injector.core :as core]
            [mcp-injector.test-mcp-server :as mcp-server]
            [mcp-injector.test-llm-server :as llm-server]))

(defn- start-system []
  (let [mcp (mcp-server/start-test-mcp-server)
        llm (llm-server/start-test-llm-server)
        mcp-config {:servers {:test {:url (str "http://localhost:" (:port mcp) "/mcp")
                                     :tools ["get_secret"]}}
                    :governance {:passthrough-trust {"read_file" :restore
                                                     "exec" :block}}}
        injector (core/start-server (assoc mcp-config
                                           :llm-url (str "http://localhost:" (:port llm))
                                           :port 0))]
    {:mcp mcp :llm llm :injector injector}))

(defn- stop-system [sys]
  (mcp-server/stop-server (:mcp sys))
  (llm-server/stop-server (:llm sys))
  (core/stop-server (:injector sys)))

(deftest passthrough-restoration-test
  (let [sys (start-system)
        injector-url (str "http://localhost:" (:port (:injector sys)) "/v1/chat/completions")]
    (try
      (testing "Simple passthrough restoration"
        ;; 1. Seed the vault with a user message containing PII
        ;; 2. LLM responds with a passthrough tool call using that PII
        (llm-server/set-next-response (:llm sys)
                                      {:choices [{:message {:role "assistant"
                                                            :tool_calls [{:id "call_1"
                                                                          :type "function"
                                                                          :function {:name "read_file"
                                                                                     :arguments "{\"path\": \"[EMAIL_ADDRESS_a35e2662]\"}"}}]}}]})

        (let [resp (http/post injector-url
                              {:body (json/generate-string
                                      {:model "test-model"
                                       :messages [{:role "user" :content "My email is wes@example.com. Read the file."}]})})]
          (is (= 200 (:status resp)))
          (let [body (json/parse-string (:body resp) true)
                tool-call (get-in body [:choices 0 :message :tool_calls 0])]
            (is (= "read_file" (get-in tool-call [:function :name])))
            ;; Should be restored to the original email because of :restore trust
            (is (str/includes? (get-in tool-call [:function :arguments]) "wes@example.com")))))

      (testing "Mixed batch priority (Strict Priority logic)"
        ;; 1. LLM returns mixed batch
        (llm-server/set-next-response (:llm sys)
                                      {:choices [{:message {:role "assistant"
                                                            :tool_calls [{:id "call_internal"
                                                                          :type "function"
                                                                          :function {:name "mcp__test__get_secret"
                                                                                     :arguments "{}"}}
                                                                         {:id "call_passthrough"
                                                                          :type "function"
                                                                          :function {:name "read_file"
                                                                                     :arguments "{\"content\": \"[EMAIL_ADDRESS_a35e2662]\"}"}}]}}]})

        ;; 2. LLM returns only passthrough after receiving secret (recursive step)
        (llm-server/set-next-response (:llm sys)
                                      {:choices [{:message {:role "assistant"
                                                            :tool_calls [{:id "call_passthrough_2"
                                                                          :type "function"
                                                                          :function {:name "read_file"
                                                                                     :arguments "{\"content\": \"[EMAIL_ADDRESS_a35e2662]\"}"}}]}}]})

        ;; Internal tool execution result
        (mcp-server/set-mcp-response (:mcp sys) "get_secret" {:secret "top-secret-123"})

        (let [resp (http/post injector-url
                              {:body (json/generate-string
                                      {:model "test-model"
                                       :messages [{:role "user" :content "My email is wes@example.com. Get secret and read."}]})})]
          (is (= 200 (:status resp)))
          (let [body (json/parse-string (:body resp) true)
                tool-calls (get-in body [:choices 0 :message :tool_calls])]
            ;; Final response should only contain the handoff tool calls from the last LLM interaction
            (is (= 1 (count tool-calls)))
            (is (= "read_file" (get-in (first tool-calls) [:function :name])))
            ;; Passthrough tool should have restored PII
            (is (str/includes? (get-in (first tool-calls) [:function :arguments]) "wes@example.com")))))

      (testing "Malformed JSON arguments handling"
        (llm-server/set-next-response (:llm sys)
                                      {:choices [{:message {:role "assistant"
                                                            :tool_calls [{:id "call_bad_json"
                                                                          :type "function"
                                                                          :function {:name "read_file"
                                                                                     :arguments "{\"path\": \"unterminated string...}"}}]}}]})
        (let [resp (http/post injector-url
                              {:body (json/generate-string
                                      {:model "test-model"
                                       :messages [{:role "user" :content "Break things."}]})})]
          (is (= 200 (:status resp)))
          (let [body (json/parse-string (:body resp) true)
                tool-call (get-in body [:choices 0 :message :tool_calls 0])]
            ;; Should return the tool call with an error message in arguments
            (is (str/includes? (get-in tool-call [:function :arguments]) "Malformed JSON arguments")))))

      (finally
        (stop-system sys)))))