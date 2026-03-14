(ns mcp-injector.restoration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(def test-state (atom {}))

(use-fixtures :once
  (fn [f]
    (let [llm (test-llm/start-server)
          mcp (test-mcp/start-server)]
      (swap! test-state assoc :llm llm :mcp mcp)
      (let [injector (core/start-server
                      {:port 0
                       :host "127.0.0.1"
                       :llm-url (str "http://localhost:" (:port llm))
                       :mcp-servers {:servers
                                     {:trusted-db
                                      {:url (str "http://localhost:" (:port mcp))
                                       :tools ["query"]
                                       :trust :restore}
                                      :untrusted-api
                                      {:url (str "http://localhost:" (:port mcp))
                                       :tools ["send"]
                                       :trust :none}}}})]
        (swap! test-state assoc :injector injector)
        (try
          (f)
          (finally
            (core/stop-server injector)
            (test-llm/stop-server llm)
            (test-mcp/stop-server mcp)))))))

(deftest test-secret-redaction-and-restoration
  (testing "End-to-end Redact -> Decide -> Restore flow"
    (let [{:keys [injector llm mcp]} @test-state
          port (:port injector)]

      ;; 1. Setup MCP to return a secret
      ((:set-tools! mcp)
       {:query {:description "Query database"
                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
                :handler (fn [args]
                           (if (or (:email args) (get args "email"))
                             {:status "success" :received (or (:email args) (get args "email"))}
                             {:email "wes@example.com" :secret "super-secret-123"}))}})

      ;; 2. LLM Turn 1: Get data (will be redacted)
      (test-llm/set-next-response llm
                                  {:role "assistant"
                                   :tool_calls [{:id "call_1"
                                                 :function {:name "mcp__trusted-db__query"
                                                            :arguments "{\"q\":\"select user\"}"}}]})

      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
      ;; Token is deterministic: SHA256("EMAIL_ADDRESS|wes@example.com|test-request-id-12345") -> a35e2662
      (test-llm/set-next-response llm
                                  {:role "assistant"
                                   :content "I found the user. Now updating."
                                   :tool_calls [{:id "call_2"
                                                 :function {:name "mcp__trusted-db__query"
                                                            :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})

      ;; Final response
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})

      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content "Update user wes"}]
                                          :stream false
                                          :extra_body {:request-id "test-request-id-12345"}})
                                  :headers {"Content-Type" "application/json"}})]
        (is (= 200 (:status response)))

        ;; Verify MCP received the RESTORED value in the second call
        (let [mcp-requests @(:received-requests mcp)
              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
              update-call (last tool-calls)
              ;; Arguments in MCP request is a JSON string, parse it
              args-str (-> update-call :body :params :arguments)
              args (json/parse-string args-str true)]
          (is (= "wes@example.com" (:email args))))

        ;; Verify LLM received REDACTED token (not original) in tool result
        (let [llm-requests @(:received-requests llm)
              ;; Find the request where LLM called tool (has tool_calls)
              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
              ;; Get the tool result message that follows the tool call
              msgs (:messages tool-call-req)
              tool-result-msg (last msgs)]
          ;; LLM should see token, not original email
          (is (some? tool-result-msg))
          (is (= "tool" (:role tool-result-msg)))
          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))

(defn -main [& _args]
  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
    (System/exit (if (zero? (:fail result)) 0 1))))
