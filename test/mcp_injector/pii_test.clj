(ns mcp-injector.pii-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mcp-injector.pii :as pii]))

(deftest scan-and-redact-test
  (testing "Email Address"
    (let [input "Contact me at user@example.com"
          config {:mode :replace :env {}}
          result (pii/scan-and-redact input config)]
      (is (= "Contact me at [EMAIL_ADDRESS]" (:text result)))
      (is (= #{:EMAIL_ADDRESS} (set (:detected result))))))

  (testing "IBAN Code (Case Insensitive)"
    (let [input "My IBAN is de89370400440532013000."
          config {:mode :replace :env {}}
          result (pii/scan-and-redact input config)]
      (is (= "My IBAN is [IBAN_CODE]." (:text result)))
      (is (= #{:IBAN_CODE} (set (:detected result))))))

  (testing "Mask Mode (Fixed Length)"
    (let [input "Contact me at user@example.com"
          config {:mode :mask :env {}}
          result (pii/scan-and-redact input config)]
      (is (= "Contact me at ********" (:text result)))))

  (testing "High Entropy Secrets (With Diversity)"
    (let [input "My API key is sk-proj-a1b2c3D4E5f6G7h8I9j0K1l2M3n4O5p6"
          config {:mode :replace :env {} :entropy-threshold 4.0}
          result (pii/scan-and-redact input config)]
      (is (= "My API key is [HIGH_ENTROPY_SECRET]" (:text result)))
      (is (= #{:HIGH_ENTROPY_SECRET} (set (:detected result))))))

  (testing "Environment Variables (Length Check)"
    (let [input "The secret value is secret_12345"
          config {:mode :replace :env {"MY_SECRET" "secret_12345"}}
          result (pii/scan-and-redact input config)]
      (is (contains? (set (:detected result)) :ENV_VAR_MY_SECRET))
      (is (str/includes? (:text result) "[ENV_VAR_MY_SECRET]")))))

(deftest shannon-entropy-test
  (testing "Low Entropy"
    (is (< (pii/shannon-entropy "aaaaaa") 1.0)))
  (testing "High Entropy"
    (is (> (pii/shannon-entropy "sk-proj-a1b2c3D4E5f6G7h8I9j0K1l2M3n4O5p6") 4.0))))

(deftest recursion-depth-limit-test
  (testing "Redact-impl handles deeply nested structures without StackOverflowError"
    (let [;; Create deeply nested map (1000 levels)
          deep-nested (loop [i 1000 data "secret@example.com"]
                        (if (zero? i)
                          data
                          (recur (dec i) {:nested data})))
          config {:salt "test-salt"}
          [result vault detected] (pii/redact-data deep-nested config)]
      ;; Should not throw StackOverflowError
      (is (map? result))
      (is (map? vault))
      (is (vector? detected))
      ;; Should have redacted the email
      (is (some #(= :EMAIL_ADDRESS %) detected)))))

(deftest real-world-patterns-test
  (testing "AWS Access Key ID"
    (let [input "AKIAIOSFODNN7EXAMPLE"
          config {:patterns [{:id :AWS_ACCESS_KEY_ID
                              :pattern #"\b(AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}\b"
                              :label "[AWS_ACCESS_KEY_ID]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[AWS_ACCESS_KEY_ID]"))
      (is (= #{:AWS_ACCESS_KEY_ID} (set (:detected result))))))

  (testing "AWS Secret Access Key"
    (let [input "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
          config {:patterns [{:id :AWS_SECRET_ACCESS_KEY
                              :pattern #"\b[A-Za-z0-9/+=]{40}\b"
                              :label "[AWS_SECRET_ACCESS_KEY]"}]}
          result (pii/scan-and-redact input config)]
      ;; Should be caught by entropy scanner
      (is (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]"))
      (is (= #{:HIGH_ENTROPY_SECRET} (set (:detected result))))))

  (testing "GitHub Personal Access Token"
    (let [input "ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
          config {:patterns [{:id :GITHUB_TOKEN
                              :pattern #"\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,}\b"
                              :label "[GITHUB_TOKEN]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[GITHUB_TOKEN]"))
      (is (= #{:GITHUB_TOKEN} (set (:detected result))))))

  (testing "Stripe API Key"
    (let [input "sk_test_abcdefghijklmnopqrstuvwxyz01234567890"
          config {:patterns [{:id :STRIPE_API_KEY
                              :pattern #"\b(sk|pk)_(live|test)_[a-zA-Z0-9]{24,}\b"
                              :label "[STRIPE_API_KEY]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[STRIPE_API_KEY]"))
      (is (= #{:STRIPE_API_KEY} (set (:detected result)))))))
