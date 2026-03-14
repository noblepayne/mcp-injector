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
