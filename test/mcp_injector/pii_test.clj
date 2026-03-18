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

(deftest new-service-patterns-test
  (testing "OpenRouter API key"
    (let [input "My OpenRouter key is sk-or-v1-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          config {:patterns [{:id :OPENROUTER_API_KEY
                              :pattern #"\bsk-or-v1-[a-f0-9]{64}\b"
                              :label "[OPENROUTER_API_KEY]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[OPENROUTER_API_KEY]"))
      (is (= #{:OPENROUTER_API_KEY} (set (:detected result))))))

  (testing "OpenAI new format (sk-proj)"
    (let [input "The project key is sk-proj-a1b2c3D4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v2W3x4Y5z6"
          config {:patterns [{:id :OPENAI_PROJECT_KEY
                              :pattern #"\bsk-proj-[a-zA-Z0-9]{48}\b"
                              :label "[OPENAI_PROJECT_KEY]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[OPENAI_PROJECT_KEY]"))
      (is (= #{:OPENAI_PROJECT_KEY} (set (:detected result))))))

  (testing "Anthropic API key"
    (let [input "Anthropic: ant-api-key-v1-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
          config {:patterns [{:id :ANTHROPIC_API_KEY
                              :pattern #"\bant-api-key-v1-[a-zA-Z0-9_-]{90,100}\b"
                              :label "[ANTHROPIC_API_KEY]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[ANTHROPIC_API_KEY]"))
      (is (= #{:ANTHROPIC_API_KEY} (set (:detected result))))))

  (testing "Google Gemini API key (AIzaSy...)"
    (let [input "Gemini key: AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
          config {:patterns [{:id :GOOGLE_GEMINI_API_KEY
                              :pattern #"\bAIzaSy[a-zA-Z0-9_-]{33}\b"
                              :label "[GOOGLE_GEMINI_API_KEY]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[GOOGLE_GEMINI_API_KEY]"))
      (is (= #{:GOOGLE_GEMINI_API_KEY} (set (:detected result))))))

  (testing "HuggingFace token"
    (let [input "HuggingFace token hf_abcdefghijklmnopqrstuvwxyz0123456789ABCD"
          config {:patterns [{:id :HUGGINGFACE_TOKEN
                              :pattern #"\bhf_[a-zA-Z0-9]{34,}\b"
                              :label "[HUGGINGFACE_TOKEN]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[HUGGINGFACE_TOKEN]"))
      (is (= #{:HUGGINGFACE_TOKEN} (set (:detected result))))))

  (testing "Generic Bearer token"
    (let [input "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
          config {:patterns [{:id :BEARER_TOKEN
                              :pattern #"\bBearer\s+[a-zA-Z0-9._-]{20,}\b"
                              :label "[BEARER_TOKEN]"}]}
          result (pii/scan-and-redact input config)]
      (is (str/includes? (:text result) "[BEARER_TOKEN]"))
      (is (= #{:BEARER_TOKEN} (set (:detected result)))))))

(deftest regex-character-class-security-test
  (testing "Hyphen position in character classes must not create ASCII ranges"
    (let [;; Test that our fixed patterns don't match problematic characters
          anthropic-pattern #"\bant-api-key-v1-[a-zA-Z0-9_-]{90,100}\b"
          gemini-pattern #"\bAIzaSy[a-zA-Z0-9_-]{33}\b"]
      ;; Characters that would match in buggy [a-zA-Z0-9-_] range but shouldn't in [a-zA-Z0-9_-]
      (is (nil? (re-find anthropic-pattern "ant-api-key-v1-@"))
          "Should NOT match @ (ASCII 64) - would be in buggy hyphen range")
      (is (nil? (re-find anthropic-pattern "ant-api-key-v1-["))
          "Should NOT match [ (ASCII 91) - would be in buggy hyphen range")
      (is (nil? (re-find anthropic-pattern "ant-api-key-v1-\\"))
          "Should NOT match \\ (ASCII 92) - would be in buggy hyphen range")
      (is (nil? (re-find anthropic-pattern "ant-api-key-v1-^"))
          "Should NOT match ^ (ASCII 94) - would be in buggy hyphen range")
      (is (nil? (re-find anthropic-pattern "ant-api-key-v1-`"))
          "Should NOT match ` (ASCII 96) - would be in buggy hyphen range")

      ;; Valid characters SHOULD match
      (is (some? (re-find gemini-pattern "AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456789"))
          "Should match valid Gemini key")
      (is (some? (re-find anthropic-pattern (str "ant-api-key-v1-" (apply str (take 90 (cycle ["a" "0"]))))))
          "Should match valid Anthropic key"))))

(deftest short-high-entropy-test
  (testing "Short high-entropy secrets should be caught with tighter thresholds"
    (let [input "Password: P@ssw0rd!S3cur3"
          config {:mode :replace :env {} :entropy-threshold 3.8}
          result (pii/scan-and-redact input config)]
      ;; This is 15 chars, diverse (upper, lower, digit, special), entropy ~3.7
      (is (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]"))
      (is (= #{:HIGH_ENTROPY_SECRET} (set (:detected result))))))

  (testing "13-character diverse secret (borderline case)"
    (let [input "Key: Xy9!Zp2@Qn8#K"
          config {:mode :replace :env {} :entropy-threshold 3.8}
          result (pii/scan-and-redact input config)]
      ;; This should be caught if min length is 13
      (is (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]"))
      (is (= #{:HIGH_ENTROPY_SECRET} (set (:detected result))))))

  (testing "Architectural strings should NOT be flagged"
    (let [input "Tool: mcp__server__tool"
          config {:mode :replace :env {} :entropy-threshold 3.8}
          result (pii/scan-and-redact input config)]
      ;; mcp__server__tool has only lowercase and underscores (2 classes)
      (is (not (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]")))
      (is (not (contains? (set (:detected result)) :HIGH_ENTROPY_SECRET))))))

(deftest e2e-redact-and-restore-test
  (testing "Aggressively redacts but successfully restores false positives"
    (let [input-text (str "Anthropic key: ant-api-key-v1-abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 "
                          "Google Gemini: AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456789 "
                          "My tool is mcp__server__tool "
                          "Bearer token: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")
          config {:salt "test-salt"}
          ;; Redact the data
          [redacted-data vault _] (pii/redact-data input-text config)]
      ;; Ensure keys are redacted
      (is (str/includes? redacted-data "[ANTHROPIC_API_KEY"))
      (is (str/includes? redacted-data "[GOOGLE_GEMINI_API_KEY"))
      (is (str/includes? redacted-data "[BEARER_TOKEN"))
      ;; Ensure architectural identifier is NOT redacted (character diversity rule)
      (is (str/includes? redacted-data "mcp__server__tool"))
      ;; Now test the restore safety net
      (let [restored (pii/restore-tokens redacted-data vault)]
        (is (= input-text restored) "Restoration should return original input exactly")))))

(deftest boundary-truncation-test
  (testing "Regex boundaries must NOT truncate tokens ending with non-word chars"

    (testing "Bearer token ending with period followed by space"
      (let [input "My Bearer: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9. Valid?"
            config {:patterns [{:id :BEARER_TOKEN
                                :pattern #"\bBearer\s+[a-zA-Z0-9._-]{20,}(?![a-zA-Z0-9._-])"
                                :label "[BEARER_TOKEN]"}]}
            result (pii/scan-and-redact input config)]
        (is (str/includes? (:text result) "[BEARER_TOKEN]"))
        (is (not (str/includes? (:text result) ". Valid"))
            "Trailing punctuation should be captured with token")))

    (testing "Bearer token ending with hyphen followed by newline"
      (let [input "Token: Bearer abc-def-ghi-\nNext line"
            config {:patterns [{:id :BEARER_TOKEN
                                :pattern #"\bBearer\s+[a-zA-Z0-9._-]{20,}(?![a-zA-Z0-9._-])"
                                :label "[BEARER_TOKEN]"}]}
            result (pii/scan-and-redact input config)]
        (is (str/includes? (:text result) "[BEARER_TOKEN]"))
        (is (not (str/includes? (:text result) "-\n")))))

    (testing "Google Gemini key ending with underscore"
      (let [input "Key: AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456789_ is valid"
            config {:patterns [{:id :GOOGLE_GEMINI_API_KEY
                                :pattern #"\bAIzaSy[a-zA-Z0-9_-]{33}(?![a-zA-Z0-9_-])"
                                :label "[GOOGLE_GEMINI_API_KEY]"}]}
            result (pii/scan-and-redact input config)]
        (is (str/includes? (:text result) "[GOOGLE_GEMINI_API_KEY]"))
        (is (not (str/includes? (:text result) "_ is valid"))
            "Trailing underscore should be captured")))))

(deftest substring-collision-test
  (testing "Sequential replacements must not corrupt overlapping patterns"

    (testing "Short prefix overlapping with longer secret"
      (let [input "Key: sk-proj-abc def sk-proj-abcdefghijklmnopqrstuvwxyz123456789012345678"
            config {:patterns [{:id :OPENAI_PROJECT_KEY
                                :pattern #"\bsk-proj-[a-zA-Z0-9]{48}\b"
                                :label "[OPENAI_PROJECT_KEY]"}]
                    :entropy-threshold 3.8}
            result (pii/scan-and-redact input config)]
        (is (= 1 (count (re-seq #"\[OPENAI_PROJECT_KEY\]" (:text result))))
            "Only one occurrence should be redacted")
        (is (str/includes? (:text result) "sk-proj-abc")
            "Short prefix should remain")))

    (testing "Multiple secrets with shared substring"
      (let [input "First: sk-proj-aaaabbbbccccddddeeeeaaaa and Second: sk-proj-aaaabbbbccccddddeeeeaaaa"
            config {:patterns [{:id :OPENAI_PROJECT_KEY
                                :pattern #"\bsk-proj-[a-zA-Z0-9]{48}\b"
                                :label "[OPENAI_PROJECT_KEY]"}]}
            result (pii/scan-and-redact input config)]
        (is (= 2 (count (re-seq #"\[OPENAI_PROJECT_KEY\]" (:text result))))
            "Both occurrences should be redacted")))))

(deftest false-positive-architectural-test
  (testing "Common architectural patterns must NOT be flagged as high-entropy"
    (let [config {:entropy-threshold 3.8}]

      (testing "MCP tool names"
        (let [input "Tool: mcp__stripe__retrieve_customer called"
              result (pii/scan-and-redact input config)]
          (is (not (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]"))
              "MCP tool names should not be redacted")))

      (testing "JSON content types"
        (let [input "Content-Type: application/json; charset=utf-8"
              result (pii/scan-and-redact input config)]
          (is (not (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]"))
              "MIME types should not be redacted")))

      (testing "Base64 encoded but low-entropy"
        (let [input "Data: YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo= is base64"
              result (pii/scan-and-redact input config)]
          (is (not (str/includes? (:text result) "[HIGH_ENTROPY_SECRET]"))
              "Common base64 strings should not be redacted"))))))

(deftest type-preservation-test
  (testing "Redact-impl preserves collection types"
    (let [config {:salt "test-salt"}]

      (testing "sorted-map stays sorted-map"
        (let [input (sorted-map :email "test@example.com" :name "Alice")
              [result _ _] (pii/redact-data input config)]
          (is (instance? clojure.lang.PersistentTreeMap result))
          (is (= "test@example.com" (get result :email)))))

      (testing "list stays list"
        (let [input (list "secret@example.com" "other@value.com")
              [result _ _] (pii/redact-data input config)]
          (is (list? result))
          (is (= "secret@example.com" (first result)))))

      (testing "set stays set"
        (let [input #{"secret@example.com" "other@value.com"}
              [result _ _] (pii/redact-data input config)]
          (is (set? result))
          (is (contains? result "secret@example.com"))))

      (testing "vector stays vector"
        (let [input ["secret@example.com" "other@value.com"]
              [result _ _] (pii/redact-data input config)]
          (is (vector? result))
          (is (= "secret@example.com" (first result))))))))
