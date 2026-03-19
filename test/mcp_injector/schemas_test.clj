(ns mcp-injector.schemas-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.schemas :as schemas]))

(deftest validate-entry-test
  (let [valid-entry {:role "assistant"
                     :content [{:type "text" :text "hello"}]
                     :_meta {:provider "openai"
                             :model "gpt-4o"
                             :turn-index 0
                             :timestamp "2024-03-21T12:00:00Z"}}]
    (testing "Valid entry passes"
      (is (= valid-entry (schemas/validate-entry valid-entry))))

    (testing "Invalid role fails"
      (let [invalid (assoc valid-entry :role "user")]
        (is (thrown? Exception (schemas/validate-entry invalid)))))

    (testing "Missing meta fails"
      (let [invalid (dissoc valid-entry :_meta)]
        (is (thrown? Exception (schemas/validate-entry invalid)))))

    (testing "Invalid content block type fails"
      (let [invalid (assoc-in valid-entry [:content 0 :type] "invalid")]
        (is (thrown? Exception (schemas/validate-entry invalid)))))

    (testing "Tool use block validation"
      (let [tool-entry (-> valid-entry
                           (assoc :role "assistant")
                           (assoc :content [{:type "tool_use"
                                             :id "call_1"
                                             :name "mcp__db__query"
                                             :input {:q "select 1"}}]))]
        (is (= tool-entry (schemas/validate-entry tool-entry)))))

    (testing "Thinking block validation"
      (let [thinking-entry (-> valid-entry
                               (assoc :content [{:type "thinking"
                                                 :thinking "Let me see..."
                                                 :signature "sig123"}]))]
        (is (= thinking-entry (schemas/validate-entry thinking-entry)))))

    (testing "Humanized error messages"
      (try
        (schemas/validate-entry (assoc valid-entry :role "user"))
        (catch Exception e
          (let [data (ex-data e)]
            (is (contains? (:error data) "role"))
            (is (re-find #"#\{\"assistant\" \"tool\"\}" (get-in data [:error "role"])))))))))
