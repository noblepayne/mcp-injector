(ns mcp-injector.projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [mcp-injector.projection :as proj]
            [mcp-injector.openai-compat :as openai]))

(deftest strip-receipt-emoji-receipt
  (testing "strip-receipt removes emoji-style action receipt"
    (let [receipt "🔧 2 tool calls · 165ms\n\n`stripe.get_customer` {\"id\": \"cus_123\"} ✓ 45ms\n`postgres.query` {\"sql\": \"SELECT *\"} ✗ 120ms — timeout\n\n---\n\nHere is the customer information."
          result (proj/strip-receipt receipt)]
      (is (str/includes? result "Here is the customer information"))
      (is (not (str/includes? result "🔧")))
      (is (not (str/includes? result "stripe.get_customer")))
      (is (not (str/includes? result "---")))
      (is (str/starts-with? result "Here")))))

(deftest strip-receipt-ascii-receipt
  (testing "strip-receipt removes ASCII-style action receipt"
    (let [receipt "[TOOLS] 2 tool calls · 165ms\n\n`stripe.get_customer` {\"id\": \"cus_123\"} OK 45ms\n`postgres.query` {\"sql\": \"SELECT *\"} ERR 120ms\n\n---\n\nHere is the customer information."
          result (proj/strip-receipt receipt)]
      (is (str/includes? result "Here is the customer information"))
      (is (not (str/includes? result "[TOOLS]")))
      (is (not (str/includes? result "postgres.query")))
      (is (not (str/includes? result "---")))
      (is (str/starts-with? result "Here")))))

(deftest strip-receipt-no-receipt
  (testing "strip-receipt returns content unchanged when no receipt present"
    (let [content "Just a normal response with no receipt."
          result (proj/strip-receipt content)]
      (is (= result content)))))

(deftest strip-receipt-empty-string
  (testing "strip-receipt handles empty string"
    (let [result (proj/strip-receipt "")]
      (is (= "" result)))))

(deftest strip-receipt-only-receipt-no-content
  (testing "strip-receipt handles receipt-only content (edge case)"
    (let [receipt "🔧 1 tool call · 45ms\n\n`foo.bar` {} ✓ 45ms\n\n---\n\n"
          result (proj/strip-receipt receipt)]
      (is (= "" result)))))

(deftest strip-receipt-delimiter-in-content
  (testing "strip-receipt only strips first occurrence"
    (let [content "Start\n\n---\n\nMiddle\n\n---\n\nEnd"
          result (proj/strip-receipt content)]
      (is (str/includes? result "End"))
      (is (str/includes? result "Middle"))
      (is (str/starts-with? result "Middle"))
      (is (= 1 (count (re-seq #"---\n" result)))))))

(deftest strip-receipt-non-string
  (testing "strip-receipt handles non-string input gracefully"
    (is (nil? (proj/strip-receipt nil)))
    (is (= 123 (proj/strip-receipt 123)))
    (is (= [:vector] (proj/strip-receipt [:vector])))))

(deftest thinking-block-same-provider
  (testing "Thinking blocks preserved when source=outbound provider"
    (let [block {:type "thinking" :content [{:type "text" :text "Internal reasoning..."}]}
          result (proj/project-content-block block "openai" "openai" 8192)]
      (is (= block result)))))

(deftest thinking-block-different-provider
  (testing "Thinking blocks replaced when source≠outbound provider"
    (let [block {:type "thinking" :content [{:type "text" :text "Internal reasoning..."}]}
          result (proj/project-content-block block "anthropic" "openai" 8192)]
      (is (= {:type "text" :text "[reasoning from prior turn omitted: provider mismatch]"}
             result)))))

(deftest tool-result-truncation
  (testing "tool_result content is truncated at limit"
    (let [long-text (apply str (repeat 1000 "x"))
          block {:type "tool_result"
                 :content [{:type "text" :text long-text}]
                 :tool_use_id "123"}
          result (proj/project-content-block block "openai" "openai" 100)
          truncated-text (get-in result [:content 0 :text])]
      (is (str/includes? truncated-text "x"))
      (is (str/includes? truncated-text "[truncated:"))
      (is (str/includes? truncated-text "1000 bytes total"))
      (is (< (count truncated-text) 200)))))

(deftest tool-result-no-truncation-needed
  (testing "tool_result content unchanged when under limit"
    (let [short-text "Short result"
          block {:type "tool_result"
                 :content [{:type "text" :text short-text}]
                 :tool_use_id "123"}
          result (proj/project-content-block block "openai" "openai" 8192)]
      (is (= short-text (get-in result [:content 0 :text]))))))

(deftest project-entry-removes-meta
  (testing "project-entry removes _meta from entry"
    (let [entry {:role "assistant"
                 :content [{:type "text" :text "Hello"}]
                 :_meta {:provider "openai" :id "123"}}
          result (proj/project-entry entry "anthropic")]
      (is (nil? (:_meta result)))
      (is (= "assistant" (:role result))))))

(deftest project-entry-strips-receipt-from-assistant
  (testing "project-entry strips receipt from assistant message"
    (let [entry {:role "assistant"
                 :content [{:type "text"
                            :text "🔧 1 tool call · 45ms\n\n`foo.bar` {} ✓ 45ms\n\n---\n\nReal response."}]
                 :_meta {:provider "openai"}}
          result (proj/project-entry entry "anthropic")
          content-text (get-in result [:content 0 :text])]
      (is (str/includes? content-text "Real response"))
      (is (not (str/includes? content-text "🔧")))
      (is (not (str/includes? content-text "foo.bar"))))))

(deftest project-entry-preserves-user-content
  (testing "project-entry does not modify user messages"
    (let [entry {:role "user"
                 :content [{:type "text" :text "User message with --- delimiter in it"}]
                 :_meta {:provider "openai"}}
          result (proj/project-entry entry "anthropic")]
      (is (= "User message with --- delimiter in it"
             (get-in result [:content 0 :text]))))))

(deftest project-work-log-applies-to-all-entries
  (testing "project-work-log applies projection to all entries"
    (let [work-log [{:role "user" :content [{:type "text" :text "Hello"}]}
                    {:role "assistant"
                     :content [{:type "text"
                                :text "🔧 1 tool call · 45ms\n\n`foo` {} ✓ 45ms\n\n---\n\nResponse"}]
                     :_meta {:provider "openai"}}
                    {:role "assistant"
                     :content [{:type "thinking" :content [{:type "text" :text "Thinking"}]}]
                     :_meta {:provider "openai"}}]
          result (proj/project-work-log work-log "anthropic")]
      (is (= 3 (count result)))
      (is (= "Hello" (get-in (nth result 0) [:content 0 :text])))
      (is (str/includes? (get-in (nth result 1) [:content 0 :text]) "Response"))
      (is (= "[reasoning from prior turn omitted: provider mismatch]"
             (get-in (nth result 2) [:content 0 :text]))))))

(deftest work-log->openai-messages-extracts-role-and-content
  (testing "work-log->openai-messages converts to OpenAI message format"
    (let [projected [{:role "user" :content [{:type "text" :text "Hello"}]}
                     {:role "assistant" :content [{:type "text" :text "Hi"}]}]
          result (proj/work-log->openai-messages projected)]
      (is (= 2 (count result)))
      (is (= "user" (get-in result [0 :role])))
      (is (= "assistant" (get-in result [1 :role])))
      (is (vector? (get-in result [0 :content])))
      (is (nil? (:_meta (first result)))))))

(deftest truncation-boundary-at-exact-limit
  (testing "Truncation does not occur when text equals limit"
    (let [text "0123456789abcdef" ; 16 chars
          long-text (apply str (repeat 4 text))
          block {:type "tool_result"
                 :content [{:type "text" :text long-text}]
                 :tool_use_id "123"}
          result (proj/project-content-block block "openai" "openai" 64)]
      (is (not (str/includes? (get-in result [:content 0 :text]) "[truncated:"))))))

(deftest truncation-boundary-at-one-over
  (testing "Truncation occurs when text exceeds limit by 1"
    (let [text "0123456789abcdef" ; 16 chars
          long-text (apply str (repeat 5 text)) ; 80 chars
          block {:type "tool_result"
                 :content [{:type "text" :text long-text}]
                 :tool_use_id "123"}
          result (proj/project-content-block block "openai" "openai" 64)]
      (is (str/includes? (get-in result [:content 0 :text]) "[truncated:"))
      (is (str/includes? (get-in result [:content 0 :text]) "80 bytes")))))

(deftest build-receipt-no-truncation-for-errors
  (testing "build-receipt never truncates error messages"
    (let [long-error "This is a very long error message that should not be truncated because silent failures are the worst case scenario for debugging"
          receipt (openai/build-receipt
                   [{:name "foo.bar" :ms 100 :status :error :error long-error}]
                   {:receipt-style :emoji})]
      (is (str/includes? receipt long-error))
      (is (not (str/includes? receipt "…")))
      (is (str/includes? receipt "This is a very long error")))))

(deftest build-receipt-args-truncation
  (testing "build-receipt truncates args at 60 chars"
    (let [long-args {:data (apply str (repeat 10 "abc def ghi jkl "))}
          receipt (openai/build-receipt
                   [{:name "foo.bar" :args long-args :ms 50 :status :ok}]
                   {:receipt-style :emoji})
          args-line (->> (str/split-lines receipt)
                         (filter #(str/includes? % "foo.bar"))
                         first)]
      (is (str/includes? args-line "…"))
      (let [args-section (second (re-find #"`[^`]+` (.+) [✓✗]" args-line))]
        (is (<= (count args-section) 70))))))

(deftest build-receipt-errors-only-mode
  (testing "build-receipt errors-only shows receipt only when errors present"
    (let [entries [{:name "ok.tool" :ms 50 :status :ok}
                   {:name "err.tool" :ms 100 :status :error :error "failed"}]]
      (is (some? (openai/build-receipt entries {:receipt-mode :errors-only})))
      (is (nil? (openai/build-receipt [{:name "ok.tool" :ms 50 :status :ok}]
                                      {:receipt-mode :errors-only}))))))

(deftest build-receipt-off-mode
  (testing "build-receipt off mode always returns nil"
    (is (nil? (openai/build-receipt [{:name "foo" :ms 50 :status :ok}]
                                    {:receipt-mode :off})))))

(deftest integration-strip-then-project
  (testing "Full pipeline: receipt stripped, entry projected, thinking blocks handled"
    (let [work-log [{:role "assistant"
                     :content [{:type "text"
                                :text "🔧 2 tool calls · 165ms\n\n`foo` {} ✓ 45ms\n\n---\n\nThe answer is 42."}]
                     :_meta {:provider "openai"}}
                    {:role "assistant"
                     :content [{:type "thinking" :content [{:type "text" :text "Reasoning..."}]}]
                     :_meta {:provider "openai"}}]
          projected (proj/project-work-log work-log "anthropic")
          openai-msgs (proj/work-log->openai-messages projected)
          assistant-text (get-in openai-msgs [0 :content 0 :text])
          thinking-text (get-in openai-msgs [1 :content 0 :text])]
      (is (str/includes? assistant-text "The answer is 42"))
      (is (not (str/includes? assistant-text "🔧")))
      (is (= "[reasoning from prior turn omitted: provider mismatch]" thinking-text)))))
