(ns mcp-injector.restoration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mcp-injector.pii :as pii]
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
                                       :trust :none}
                                     :workspace
                                      {:url (str "http://localhost:" (:port mcp))
                                       :trust :restore}}})]
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
          port (:port injector)
          request-id "test-request-id-12345"
          secret-email "wes@example.com"
          expected-token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
      ;; Setup MCP to return a secret
      ((:set-tools! mcp)
       {:query {:description "Query database"
                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
                :handler (fn [args]
                           (if-let [email (or (:email args) (get args "email"))]
                             {:status "success" :received email}
                             {:email secret-email :secret "super-secret-123"})}})
      ;; LLM Turn 1: Get data (will be redacted)
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :tool_calls [{:id "call_1"
                                                  :function {:name "mcp__trusted-db__query"
                                                             :arguments "{\"q\":\"select user\"}"}}]})
      ;; LLM Turn 2: Receive redacted data and call another tool using the token
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :content "I found the user. Now updating."
                                    :tool_calls [{:id "call_2"
                                                  :function {:name "mcp__trusted-db__query"
                                                             :arguments (json/generate-string {:email expected-token})}}]})
      ;; Final response
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content "Update user wes"}]
                                          :stream false
                                          :extra_body {:request-id request-id}})
                                  :headers {"Content-Type" "application/json"}})]
        (is (= 200 (:status response)))
        ;; Verify MCP received the RESTORED value in the second call
        (let [mcp-requests @(:received-requests mcp)
              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
              update-call (last tool-calls)
              args-str (-> update-call :body :params :arguments)
              args (json/parse-string args-str true)]
          (is (= secret-email (:email args))))
        ;; Verify LLM received REDACTED token (not original) in tool result
        (let [llm-requests @(:received-requests llm)
              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
              msgs (:messages tool-call-req)
              tool-result-msg (last msgs)]
          (is (some? tool-result-msg))
          (is (= "tool" (:role tool-result-msg)))
          (is (str/includes? (:content tool-result-msg) expected-token))
          (is (not (str/includes? (:content tool-result-msg) secret-email))))))))

(deftest test-edit-tool-with-pii-token
  (testing "Edit tool can use restored PII tokens (fixes read->edit workflow)"
    (let [{:keys [injector llm mcp]} @test-state
          port (:port injector)
          request-id "edit-test-request-id"
          secret-email "wes@example.com"
          token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
      ;; Setup MCP with read and edit tools
      ((:set-tools! mcp)
       {:read-file
        {:description "Read file contents"
         :schema {:type "object" :properties {:path {:type "string"}}}
         :handler (fn [args] {:content secret-email})}
        :edit-file
        {:description "Edit file"
         :schema {:type "object" :properties {:path {:type "string"}
                                               :old_string {:type "string"}
                                               :new_string {:type "string"}}}
         :handler (fn [args] {:success true :received-args args})}})
      ;; LLM Turn 1: Read file
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :content "I'll read the file."
                                    :tool_calls [{:id "call_1"
                                                  :function {:name "mcp__workspace__read-file"
                                                             :arguments (json/generate-string {:path "/tmp/script.sh"})}}]})
      ;; LLM Turn 2: Uses token in edit old_string
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :content "Updating email..."
                                    :tool_calls [{:id "call_2"
                                                  :function {:name "mcp__workspace__edit-file"
                                                             :arguments (json/generate-string
                                                                         {:path "/tmp/script.sh"
                                                                          :old_string token
                                                                          :new_string "new@example.com"})}}]})
      ;; Final response
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content "Update the email in /tmp/script.sh"}]
                                          :stream false
                                          :extra_body {:request-id request-id}})
                                  :headers {"Content-Type" "application/json"}})]
        (is (= 200 (:status response)))
        ;; Verify edit tool received ACTUAL email (not token) in old_string
        (let [mcp-requests @(:received-requests mcp)
              edit-call (last (filter #(= "tools/call" (-> % :body :method)) mcp-requests))
              args-str (-> edit-call :body :params :arguments)
              args (json/parse-string args-str true)]
          (is (= secret-email (:old_string args))))))))

(defn -main [& _args]
  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
    (System/exit (if (zero? (:fail result)) 0 1))))
