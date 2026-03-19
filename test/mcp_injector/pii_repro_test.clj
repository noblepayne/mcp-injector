(ns mcp-injector.pii-repro-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.pii :as pii]))

(deftest repro-pii-redaction-failure
  (testing "Stripe production key redaction"
    (let [key "sk_production_51Nq4Yx9Zb2L8pQ0rS7gH3kD9fW2eM6aR1T8uC5vB0xZy3Q4wE9tR2yU5iO8pA1sD4fG7hJ0kL3mN6oP9qS2tV5wX8zC1bE4gH7jK0mN3pQ6sT9vW2xY5zA8bD1eF4gH7jK0mN3pQ6sT9"
          text (str "API_KEY=\"" key "\"")
          result (pii/scan-and-redact text {:proximity-check-enabled true})]
      (is (not (.contains (:text result) key)) "Production key should be redacted")))

  (testing "Stripe restricted key redaction"
    (let [key "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"
          text (str "Restricted key: " key)
          result (pii/scan-and-redact text {:proximity-check-enabled true})]
      (is (not (.contains (:text result) key)) "Restricted key should be redacted"))))
