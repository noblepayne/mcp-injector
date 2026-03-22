(ns mcp-injector.observability-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [mcp-injector.openai-compat :as openai]
            [mcp-injector.pii]))

(deftest parse-traceparent-test
  (testing "valid traceparent is parsed correctly"
    (let [result (openai/parse-traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")]
      (is (= "00" (:version result)))
      (is (= "0af7651916cd43dd8448eb211c80319c" (:trace-id result)))
      (is (= "b7ad6b7169203331" (:parent-id result)))
      (is (= "01" (:flags result)))))

  (testing "nil returns nil"
    (is (nil? (openai/parse-traceparent nil))))

  (testing "invalid format returns nil"
    (is (nil? (openai/parse-traceparent "invalid")))
    (is (nil? (openai/parse-traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331")))
    (is (nil? (openai/parse-traceparent "000-af-short-b7ad6b7169203331-01")))))

(deftest build-traceparent-test
  (testing "builds valid W3C traceparent"
    (let [result (openai/build-traceparent "0af7651916cd43dd8448eb211c80319c" "b7ad6b7169203331")]
      (is (= "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00" result)))))

(deftest build-trace-headers-test
  (testing "generates new traceparent when none exists"
    (let [headers (openai/build-trace-headers)]
      (is (some? (get headers "traceparent")))
      (is (some? (openai/parse-traceparent (get headers "traceparent"))))))

  (testing "propagates existing traceparent - preserves trace-id per W3C"
    (let [incoming {"traceparent" "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}
          headers (openai/build-trace-headers incoming)
          parsed (openai/parse-traceparent (get headers "traceparent"))]
      (is (= "0af7651916cd43dd8448eb211c80319c" (:trace-id parsed)))
      (is (= 16 (count (:parent-id parsed))))))

  (testing "generates new trace if existing is malformed"
    (let [incoming {"traceparent" "invalid"}
          headers (openai/build-trace-headers incoming)]
      (is (some? (get headers "traceparent")))
      (is (not= "invalid" (get headers "traceparent"))))))

(deftest format-tool-entry-test
  (testing "formats successful tool call"
    (let [result (openai/format-tool-entry {:name "stripe.get_customer" :ms 45 :status :ok})]
      (is (= ["stripe.get_customer" "{}" "45ms" nil :ok] result))))

  (testing "formats tool call with error"
    (let [result (openai/format-tool-entry {:name "postgres.query" :ms 120 :status :error :error "timeout"})]
      (is (= ["postgres.query" "{}" "120ms" "timeout" :error] result))
      (is (= :error (last result)))))

  (testing "returns nil for skipped tools"
    (is (nil? (openai/format-tool-entry {:name "skipped_tool" :status :skipped}))))

  (testing "handles missing ms"
    (let [result (openai/format-tool-entry {:name "test.tool" :status :ok})]
      (is (= ["test.tool" "{}" "?" nil :ok] result)))))

(deftest build-receipt-test
  (testing "returns nil for empty worklog"
    (is (nil? (openai/build-receipt []))))

  (testing "returns nil for worklog with only skipped tools"
    (is (nil? (openai/build-receipt [{:name "skipped" :status :skipped}]))))

  (testing "builds receipt with single tool"
    (let [worklog [{:name "stripe.get_customer" :args {:id "cus_123"} :ms 45 :status :ok}]
          receipt (openai/build-receipt worklog {:trace-id "abc123"})]
      (is (some? receipt))
      (is (str/includes? receipt "stripe.get_customer"))
      (is (str/includes? receipt "45ms"))
      (is (str/includes? receipt "---"))))

  (testing "builds receipt with multiple tools"
    (let [worklog [{:name "stripe.get_customer" :ms 45 :status :ok}
                   {:name "postgres.query" :ms 120 :status :ok}]
          receipt (openai/build-receipt worklog)]
      (is (some? receipt))
      (is (str/includes? receipt "stripe.get_customer"))
      (is (str/includes? receipt "postgres.query"))
      (is (str/includes? receipt "45ms"))
      (is (str/includes? receipt "120ms"))
      (is (str/includes? receipt "---"))))

  (testing "receipt is prepended correctly (starts with header, ends with delimiter)"
    (let [worklog [{:name "test.tool" :ms 10 :status :ok}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt (str \newline)))
      (is (str/ends-with? (str/trimr receipt) "---"))))

  (testing "includes total ms in header"
    (let [worklog [{:name "tool1" :ms 100 :status :ok}
                   {:name "tool2" :ms 200 :status :ok}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "300ms"))
      (is (str/includes? receipt "2 tool calls"))))

  (testing "handles mixed success/error tools"
    (let [worklog [{:name "success.tool" :ms 50 :status :ok}
                   {:name "fail.tool" :ms 30 :status :error :error "connection refused"}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "success.tool"))
      (is (str/includes? receipt "fail.tool"))
      (is (str/includes? receipt "ERR"))))

  (testing "receipt is separated from content with --- and newlines"
    (let [worklog [{:name "test.tool" :ms 10 :status :ok}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "---"))
      (is (str/ends-with? (str/trimr receipt) "---")))))

(deftest redact-for-receipt-test
  (testing "returns args unchanged when no PII"
    (let [[redacted vault detected] (openai/redact-for-receipt {:id "123"} {} {})]
      (is (= {:id "123"} redacted))
      (is (empty? vault))
      (is (empty? detected))))

  (testing "redacts API keys"
    (let [args {:api_key "sk_test_abc123def456xyz78901234567"
                :query "SELECT *"}
          [redacted vault detected] (openai/redact-for-receipt args {:patterns mcp-injector.pii/default-patterns} {})]
      (is (str/includes? (str redacted) "[STRIPE_API_KEY"))
      (is (contains? vault (first (keys vault))))
      (is (some #(= % "sk_test_abc123def456xyz78901234567") detected)))))

(deftest prime-directive-test
  (testing "receipt never contains HTML comment sentinels"
    (let [worklog [{:name "test.tool" :ms 10 :status :ok}]
          receipt (openai/build-receipt worklog)]
      (is (not (str/includes? receipt "<!--")))
      (is (not (str/includes? receipt "x-injector-v1")))))

  (testing "receipt never contains base64 blobs"
    (let [worklog [{:name "test.tool" :ms 10 :status :ok}]
          receipt (openai/build-receipt worklog)]
      (is (not (str/includes? receipt "x-injector-v1")))
      (is (not (re-find #"^[A-Za-z0-9+/=]+$" (str receipt))) "Receipt should be readable markdown, not a raw base64 blob"))))

(deftest generate-trace-id-test
  (testing "generates 32 hex chars"
    (let [trace-id (openai/generate-trace-id)]
      (is (= 32 (count trace-id)))
      (is (re-find #"^[0-9a-z]+$" trace-id))))

  (testing "generates unique values"
    (let [trace-ids (take 100 (repeatedly openai/generate-trace-id))]
      (is (= 100 (count (distinct trace-ids)))))))

(deftest work-log-entry-structure-test
  (testing "work-log entries have required keys"
    (let [entry {:name "mcp__stripe__get_customer"
                 :args {:id "cus_123"}
                 :ms 145
                 :status :ok
                 :error nil}]
      (is (contains? entry :name))
      (is (contains? entry :args))
      (is (contains? entry :ms))
      (is (contains? entry :status))
      (is (contains? entry :error))))

  (testing "status can be :ok, :error, :pii-blocked, or :policy-blocked"
    (let [statuses [:ok :error :pii-blocked :policy-blocked]]
      (doseq [status statuses]
        (let [entry {:name "test" :args {} :ms 10 :status status :error nil}]
          (is (= status (:status entry))))))))

(deftest work-log-pii-redaction-test
  (testing "work-log args are redacted for API keys"
    (let [worklog [{:name "stripe.charge"
                    :args {:api_key "sk_test_abc123def456xyz78901234567"
                           :amount 1000}
                    :ms 45
                    :status :ok
                    :error nil}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "[STRIPE_API_KEY"))
      (is (not (str/includes? receipt "sk_test_abc123def456xyz78901234567")))))

  (testing "work-log args are redacted for email addresses"
    (let [worklog [{:name "user.find"
                    :args {:email "user@example.com" :id 123}
                    :ms 30
                    :status :ok
                    :error nil}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "[EMAIL"))
      (is (not (str/includes? receipt "user@example.com")))))

  (testing "work-log args are redacted for secrets"
    (let [worklog [{:name "db.query"
                    :args {:secret "my-super-secret-key-12345-extra-long-to-match-pattern"
                           :sql "SELECT *"}
                    :ms 20
                    :status :ok
                    :error nil}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "[GENERIC_SECRET"))
      (is (not (str/includes? receipt "my-super-secret-key-12345-extra-long-to-match-pattern"))))))

(deftest work-log-receipt-integration-test
  (testing "receipt includes redacted args"
    (let [worklog [{:name "stripe.get_customer"
                    :args {:api_key "[STRIPE_API_KEY_abc123]"
                           :id "cus_123"}
                    :ms 45
                    :status :ok
                    :error nil}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "stripe.get_customer"))
      (is (str/includes? receipt "45ms"))
      (is (str/includes? receipt "[STRIPE_API_KEY"))
      (is (str/includes? receipt "---"))))

  (testing "receipt shows error status"
    (let [worklog [{:name "db.query"
                    :args {:sql "SELECT *"}
                    :ms 500
                    :status :error
                    :error "connection timeout"}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "db.query"))
      (is (str/includes? receipt "ERR"))
      (is (str/includes? receipt "connection"))))

  (testing "receipt shows policy-blocked status"
    (let [worklog [{:name "exec.run"
                    :args {}
                    :ms 0
                    :status :policy-blocked
                    :error "Tool execution denied"}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "exec.run"))
      (is (str/includes? receipt "ERR"))
      (is (str/includes? receipt "denied"))))

  (testing "receipt shows pii-blocked status"
    (let [worklog [{:name "secret.read"
                    :args {}
                    :ms 0
                    :status :pii-blocked
                    :error "PII Blocked"}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "secret.read"))
      (is (str/includes? receipt "ERR"))
      (is (str/includes? receipt "PII")))))

(deftest work-log-multi-tool-test
  (testing "receipt aggregates multiple tools correctly"
    (let [worklog [{:name "tool1" :args {} :ms 10 :status :ok :error nil}
                   {:name "tool2" :args {} :ms 20 :status :ok :error nil}
                   {:name "tool3" :args {} :ms 30 :status :ok :error nil}]
          receipt (openai/build-receipt worklog)]
      (is (str/includes? receipt "tool1"))
      (is (str/includes? receipt "tool2"))
      (is (str/includes? receipt "tool3"))
      (is (str/includes? receipt "60ms"))
      (is (str/includes? receipt "3 tool calls"))))

  (testing "receipt handles empty worklog"
    (is (nil? (openai/build-receipt []))))

  (testing "receipt handles single tool"
    (let [worklog [{:name "single" :args {} :ms 5 :status :ok :error nil}]
          receipt (openai/build-receipt worklog)]
      (is (some? receipt))
      (is (str/includes? receipt "single"))
      (is (str/includes? receipt "1 tool")))))
