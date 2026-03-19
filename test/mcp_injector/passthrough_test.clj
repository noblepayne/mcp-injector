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
        llm (llm-server/start-server)
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

(defn- extract-token [req]
  (let [content (get-in (last (:messages req)) [:content])]
    (re-find #"\[EMAIL_ADDRESS_[a-f0-9]+\]" content)))

(deftest passthrough-restoration-test
  (let [sys (start-system)
        injector-url (str "http://localhost:" (:port (:injector sys)) "/v1/chat/completions")]
    (try
      (testing "Simple passthrough restoration"
        (llm-server/set-dynamic-response!
         (:llm sys)
         (fn [req]
           (let [token (extract-token req)]
             {:role "assistant"
              :tool_calls [{:name "read_file"
                            :arguments {:path token}}]})))

        (let [resp (http/post injector-url
                              {:body (json/generate-string
                                      {:model "test-model"
                                       :messages [{:role "user" :content "My email is wes@example.com. Read the file."}]})})]
          (is (= 200 (:status resp)))
          (let [body (json/parse-string (:body resp) true)
                message (get-in body [:choices 0 :message])
                tool-call (first (:tool_calls message))]
            (is (= "read_file" (get-in tool-call [:function :name])))
            (is (str/includes? (get-in tool-call [:function :arguments]) "wes@example.com")))))

      (testing "Mixed batch priority (Strict Priority logic)"
        ;; 1. First call returns mixed batch
        (llm-server/set-dynamic-response!
         (:llm sys)
         (fn [req]
           (let [token (extract-token req)]
             {:role "assistant"
              :tool_calls [{:name "mcp__test__get_secret"
                            :arguments {}}
                           {:name "read_file"
                            :arguments {:content token}}]})))

        ;; 2. Recursive call returns only passthrough
        (llm-server/set-dynamic-response!
         (:llm sys)
         (fn [req]
           (let [token (extract-token req)]
             {:role "assistant"
              :tool_calls [{:name "read_file"
                            :arguments {:content token}}]})))

        ;; Internal tool execution result
        (mcp-server/set-mcp-response (:mcp sys) "get_secret" {:secret "top-secret-123"})

        (let [resp (http/post injector-url
                              {:body (json/generate-string
                                      {:model "test-model"
                                       :messages [{:role "user" :content "My email is wes@example.com. Get secret and read."}]})})]
          (is (= 200 (:status resp)))
          (let [body (json/parse-string (:body resp) true)
                tool-calls (get-in body [:choices 0 :message :tool_calls])]
            (is (= 1 (count tool-calls)))
            (is (= "read_file" (get-in (first tool-calls) [:function :name])))
            (is (str/includes? (get-in (first tool-calls) [:function :arguments]) "wes@example.com")))))

      (testing "Malformed JSON arguments handling"
        (llm-server/set-next-response (:llm sys)
                                      {:role "assistant"
                                       :tool_calls [{:name "read_file"
                                                     :arguments "{\"path\": \"unterminated string...}"}]})
        (let [resp (http/post injector-url
                              {:body (json/generate-string
                                      {:model "test-model"
                                       :messages [{:role "user" :content "Break things."}]})})]
          (is (= 200 (:status resp)))
          (let [body (json/parse-string (:body resp) true)
                message (get-in body [:choices 0 :message])
                tool-call (first (:tool_calls message))]
            (is (str/includes? (get-in tool-call [:function :arguments]) "Malformed JSON arguments")))))

      (finally
        (stop-system sys)))))