(ns mcp-injector.openai-compat-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.openai-compat :as openai]))

;; ═══════════════════════════════════════════════════════════════
;; Predicate tests
;; ═══════════════════════════════════════════════════════════════

(deftest o-series-detection-test
  (testing "O-series predicate correctly identifies OpenAI reasoning models"
    ;; Current models
    (is (openai/o-series? "o1"))
    (is (openai/o-series? "o1-mini"))
    (is (openai/o-series? "o1-preview"))
    (is (openai/o-series? "o1-pro"))
    (is (openai/o-series? "o3"))
    (is (openai/o-series? "o3-mini"))
    (is (openai/o-series? "o3-pro"))
    (is (openai/o-series? "o4-mini"))
    (is (openai/o-series? "o-2024-12-17"))
    ;; Non-o-series
    (is (not (openai/o-series? "gpt-4o")))
    (is (not (openai/o-series? "gpt-4o-mini")))
    (is (not (openai/o-series? "claude-3-opus")))
    (is (not (openai/o-series? "qwen3-235b")))
    (is (not (openai/o-series? nil)))))

(deftest o1-preview-detection-test
  ;; o1-preview deprecated July 2025, kept for self-hosted/proxy compat
  (testing "o1-preview predicate correctly identifies o1-preview models"
    (is (openai/o1-preview? "o1-preview"))
    (is (openai/o1-preview? "o1-preview-2024-09-12"))
    (is (not (openai/o1-preview? "o1")))
    (is (not (openai/o1-preview? "o1-mini")))
    (is (not (openai/o1-preview? "gpt-4o")))))

(deftest o-series-slash-prefix-test
  (testing "O-series predicate handles provider-prefixed model strings"
    (is (openai/o-series? "openai/o1-mini"))
    (is (openai/o-series? "openai/o3-mini"))
    (is (openai/o-series? "openai/o4-mini"))
    (is (openai/o-series? "openai/o3-pro"))
    (is (openai/o-series? "openrouter/openai/o1-mini"))
    (is (openai/o-series? "openrouter/openai/o4-mini"))
    (is (not (openai/o-series? "openai/gpt-4o")))
    (is (not (openai/o-series? "anthropic/claude-3-opus")))))

(deftest o1-preview-slash-prefix-test
  ;; o1-preview deprecated July 2025, kept for self-hosted/proxy compat
  (testing "o1-preview predicate handles provider-prefixed model strings"
    (is (openai/o1-preview? "openai/o1-preview"))
    (is (openai/o1-preview? "openai/o1-preview-2024-09-12"))
    (is (not (openai/o1-preview? "openai/o1-mini")))
    (is (not (openai/o1-preview? "openai/gpt-4o")))))

(deftest o-series-strict-boolean-test
  (testing "Predicates return strict booleans, not nil"
    (is (true? (openai/o-series? "o1")))
    (is (false? (openai/o-series? nil)))
    (is (false? (openai/o-series? "gpt-4o")))
    (is (true? (openai/o1-preview? "o1-preview")))
    (is (false? (openai/o1-preview? nil)))
    (is (false? (openai/o1-preview? "o1")))))

;; ═══════════════════════════════════════════════════════════════
;; O-series normalization tests
;; Keys use underscore format (matching parse-body output)
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-o-series-test
  (testing "O-series normalization converts system role to developer and renames max_tokens"
    (let [request {:model "o1-mini"
                   :messages [{:role "system" :content "You are a helpful assistant."}
                              {:role "user" :content "Hello"}]
                   :max_tokens 1000}
          normalized (openai/normalize-request request)]
      (is (= "developer" (:role (first (:messages normalized)))))
      (is (= "user" (:role (second (:messages normalized)))))
      (is (nil? (:max_tokens normalized)))
      (is (= 1000 (:max_completion_tokens normalized))))))

(deftest normalize-request-o4-mini-test
  (testing "o4-mini normalization works (the live model that was previously missed)"
    (let [request {:model "o4-mini"
                   :messages [{:role "system" :content "You are a coding assistant."}
                              {:role "user" :content "Write hello world"}]
                   :max_tokens 500
                   :temperature 0.7}
          normalized (openai/normalize-request request)]
      (is (= "developer" (:role (first (:messages normalized)))))
      (is (= 500 (:max_completion_tokens normalized)))
      (is (nil? (:max_tokens normalized)))
      (is (nil? (:temperature normalized))))))

(deftest normalize-request-non-o-series-test
  (testing "Non-O-series models are not normalized"
    (let [request {:model "gpt-4o"
                   :messages [{:role "system" :content "You are a helpful assistant."}
                              {:role "user" :content "Hello"}]
                   :max_tokens 1000}
          normalized (openai/normalize-request request)]
      (is (= "system" (:role (first (:messages normalized)))))
      (is (= 1000 (:max_tokens normalized)))
      (is (nil? (:max_completion_tokens normalized))))))

(deftest normalize-request-with-disabled-compat-test
  (testing "O-series compat can be disabled via options"
    (let [request {:model "o1-mini"
                   :messages [{:role "system" :content "You are a helpful assistant."}]
                   :max_tokens 1000}
          normalized (openai/normalize-request request {:o-series-compat false})]
      (is (= "system" (:role (first (:messages normalized)))))
      (is (= 1000 (:max_tokens normalized))))))

(deftest normalize-request-max-completion-tokens-already-present-test
  (testing "Does not clobber client-provided max_completion_tokens"
    (let [request {:model "o1-mini"
                   :messages [{:role "system" :content "You are a helpful assistant."}]
                   :max_tokens 500
                   :max_completion_tokens 1000}
          normalized (openai/normalize-request request)]
      (is (= 1000 (:max_completion_tokens normalized)))
      (is (nil? (:max_tokens normalized))))))

(deftest normalize-request-max-completion-tokens-only-test
  (testing "Passes through max_completion_tokens when client sends it directly (no max_tokens)"
    (let [request {:model "o1-mini"
                   :messages [{:role "system" :content "You are a helpful assistant."}]
                   :max_completion_tokens 2000}
          normalized (openai/normalize-request request)]
      (is (= 2000 (:max_completion_tokens normalized)))
      (is (nil? (:max_tokens normalized))))))

;; ═══════════════════════════════════════════════════════════════
;; O-series param stripping tests (all o-series, not just o1-preview)
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-o-series-strips-params-test
  (testing "ALL o-series models strip unsupported parameters"
    (doseq [model ["o1" "o1-mini" "o3" "o3-mini" "o3-pro" "o4-mini" "o1-preview"]]
      (let [request {:model model
                     :messages [{:role "system" :content "You are a helpful assistant."}]
                     :max_tokens 1000
                     :temperature 0.5
                     :top_p 0.9
                     :frequency_penalty 0.1
                     :presence_penalty 0.1}
            normalized (openai/normalize-request request)]
        (is (= "developer" (:role (first (:messages normalized))))
            (str model ": role should be developer"))
        (is (= 1000 (:max_completion_tokens normalized))
            (str model ": max_completion_tokens should be set"))
        (is (nil? (:temperature normalized))
            (str model ": temperature should be stripped"))
        (is (nil? (:top_p normalized))
            (str model ": top_p should be stripped"))
        (is (nil? (:frequency_penalty normalized))
            (str model ": frequency_penalty should be stripped"))
        (is (nil? (:presence_penalty normalized))
            (str model ": presence_penalty should be stripped"))))))

;; ═══════════════════════════════════════════════════════════════
;; Loop pinning tests
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-loop-pinning-test
  (testing "Loop pinning applies temperature and effort for non-o-series"
    (let [request {:model "gpt-4o"
                   :messages [{:role "user" :content "Hello"}]
                   :temperature 0.7}
          normalized (openai/normalize-request request {:loop-pinning true
                                                        :pin-temp 0.1
                                                        :pin-effort :low
                                                        :iteration 1})]
      (is (= 0.1 (:temperature normalized)))
      (is (= :low (:reasoning_effort normalized))))))

(deftest normalize-request-loop-pinning-iteration-0-test
  (testing "Loop pinning does NOT apply on iteration 0 (first call)"
    (let [request {:model "gpt-4o"
                   :messages [{:role "user" :content "Hello"}]
                   :temperature 0.7}
          normalized (openai/normalize-request request {:loop-pinning true
                                                        :pin-temp 0.1
                                                        :pin-effort :low
                                                        :iteration 0})]
      (is (= 0.7 (:temperature normalized)))
      (is (nil? (:reasoning_effort normalized))))))

(deftest normalize-request-loop-pinning-multiple-iterations-test
  (testing "Loop pinning persists across iterations 1, 2, 3..."
    (let [request {:model "gpt-4o"
                   :messages [{:role "user" :content "Hello"}]
                   :temperature 0.7}]
      (doseq [iter [1 2 3 5 10]]
        (let [normalized (openai/normalize-request request
                                                   {:loop-pinning true
                                                    :pin-temp 0.1
                                                    :pin-effort :low
                                                    :iteration iter})]
          (is (= 0.1 (:temperature normalized))
              (str "temperature should be pinned at iteration " iter))
          (is (= :low (:reasoning_effort normalized))
              (str "reasoning_effort should be pinned at iteration " iter)))))))

(deftest normalize-request-loop-pinning-o-series-safety-test
  (testing "Loop pinning for o-series: only reasoning_effort, NOT temperature"
    (doseq [model ["o1" "o3" "o3-mini" "o4-mini" "o3-pro"]]
      (let [request {:model model
                     :messages [{:role "user" :content "Hello"}]
                     :temperature 0.7}
            normalized (openai/normalize-request request
                                                 {:loop-pinning true
                                                  :pin-temp 0.1
                                                  :pin-effort :low
                                                  :iteration 1})]
        (is (nil? (:temperature normalized))
            (str model ": temperature should NOT be re-added by loop pinning"))
        (is (= :low (:reasoning_effort normalized))
            (str model ": reasoning_effort should be pinned"))))))

;; ═══════════════════════════════════════════════════════════════
;; Edge case tests
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-empty-messages-test
  (testing "Normalization handles nil or empty messages gracefully"
    (let [req1 {:model "o1" :messages nil}
          req2 {:model "o1" :messages []}
          res1 (openai/normalize-request req1)
          res2 (openai/normalize-request req2)]
      (is (= [] (:messages res1)) "nil messages should become empty vector")
      (is (= [] (:messages res2)) "empty messages should remain empty vector"))))

(deftest normalize-request-string-effort-coercion-test
  (testing "Coerces string-based reasoning_effort (from Nix/EDN) to keyword"
    (let [req {:model "o1" :messages [{:role "user" :content "hi"}]}
          res (openai/normalize-request req {:loop-pinning true :iteration 1 :pin-effort "medium"})]
      (is (= :medium (:reasoning_effort res)) "string 'medium' should become keyword :medium"))))

;; ═══════════════════════════════════════════════════════════════
;; Extra field preservation tests
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-preserves-extra-fields-test
  (testing "Normalization preserves extra_body, include_search, plugins"
    (let [request {:model "o1-mini"
                   :messages [{:role "system" :content "You are a helpful assistant."}]
                   :max_tokens 1000
                   :include_search true
                   :plugins [{"id" "web"}]
                   :extra_body {:reasoning_effort "low"}}
          normalized (openai/normalize-request request)]
      (is (= true (:include_search normalized)))
      (is (= [{"id" "web"}] (:plugins normalized)))
      (is (= {:reasoning_effort "low"} (:extra_body normalized))))))
