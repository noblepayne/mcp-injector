(ns mcp-injector.restoration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
          mcp (test-mcp/start-test-mcp-server)]
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
                                       :tools ["read-file" "edit-file"]
                                       :trust :restore}}}})]
        (swap! test-state assoc :injector injector)
        (try
          (f)
          (finally
            (core/stop-server injector)
            (test-llm/stop-server llm)
            (test-mcp/stop-server mcp)))))))

(use-fixtures :each
  (fn [f]
    (test-llm/clear-responses (:llm @test-state))
    (reset! (:received-requests (:llm @test-state)) [])
    (f)))

(deftest test-secret-redaction-and-restoration
  (testing "End-to-end Redact -> Decide -> Restore flow"
    (let [{:keys [injector llm mcp]} @test-state
          port (:port injector)
          request-id "test-request-id-12345"
          secret-email "wes@example.com"
          salt (core/derive-pii-salt request-id)
          expected-token (pii/generate-token :EMAIL_ADDRESS secret-email salt)]
      ((:set-tools! mcp)
       {:query {:description "Query database"
                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
                :handler (fn [args]
                           (if-let [email (or (:email args) (get args "email"))]
                             {:status "success" :received email}
                             {:email secret-email :secret "super-secret-123"}))}})
      (test-llm/set-next-response llm
                                  {:role "assistant"
                                   :tool_calls [{:id "call_1"
                                                 :function {:name "mcp__trusted-db__query"
                                                            :arguments (json/generate-string {:q "select user"})}}]})
      (test-llm/set-next-response llm
                                  {:role "assistant"
                                   :content "I found the user. Now updating."
                                   :tool_calls [{:id "call_2"
                                                 :function {:name "mcp__trusted-db__query"
                                                            :arguments (json/generate-string {:email expected-token})}}]})
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content (str "Update user " secret-email)}]
                                          :stream false
                                          :extra_body {:session-id request-id}})
                                  :headers {"Content-Type" "application/json"}})]
        (is (= 200 (:status response)))
        (let [mcp-requests @(:received-requests mcp)
              tool-calls (filter #(= "tools/call" (get-in % [:body :method])) mcp-requests)
              update-call (last tool-calls)
              args-str (get-in update-call [:body :params :arguments])
              args (when args-str (json/parse-string args-str true))]
          (is (= secret-email (or (:email args) (get args "email")))))))))

(deftest test-edit-tool-with-pii-token
  (testing "Edit tool can use restored PII tokens (fixes read->edit workflow)"
    (let [{:keys [injector llm mcp]} @test-state
          port (:port injector)
          request-id "edit-test-request-id"
          secret-email "wes@example.com"
          salt (core/derive-pii-salt request-id)
          token (pii/generate-token :EMAIL_ADDRESS secret-email salt)]
      ((:set-tools! mcp)
       {:read-file
        {:description "Read file contents"
         :schema {:type "object" :properties {:path {:type "string"}}}
         :handler (fn [_] {:content secret-email})}
        :edit-file
        {:description "Edit file"
         :schema {:type "object" :properties {:path {:type "string"}
                                              :old_string {:type "string"}
                                              :new_string {:type "string"}}}
         :handler (fn [args] {:success true :received-args args})}})
      (test-llm/set-next-response llm
                                  {:role "assistant"
                                   :content "I'll read the file."
                                   :tool_calls [{:id "call_1"
                                                 :function {:name "mcp__workspace__read-file"
                                                            :arguments (json/generate-string {:path "/tmp/script.sh"})}}]})
      (test-llm/set-next-response llm
                                  {:role "assistant"
                                   :content "Updating email..."
                                   :tool_calls [{:id "call_2"
                                                 :function {:name "mcp__workspace__edit-file"
                                                            :arguments (json/generate-string
                                                                        {:path "/tmp/script.sh"
                                                                         :old_string token
                                                                         :new_string "new@example.com"})}}]})
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content (str "Update the email " secret-email " in /tmp/script.sh")}]
                                          :stream false
                                          :extra_body {:session-id request-id}})
                                  :headers {"Content-Type" "application/json"}})
            mcp-requests @(:received-requests mcp)
            tool-calls (filter #(= "tools/call" (get-in % [:body :method])) mcp-requests)
            edit-call (last tool-calls)
            args-str (when edit-call (get-in edit-call [:body :params :arguments]))
            args (when args-str (json/parse-string args-str true))]
        (is (= 200 (:status response)))
        (is (= secret-email (or (:old_string args) (get args "old_string"))))))))
