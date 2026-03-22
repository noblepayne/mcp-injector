(ns mcp-injector.openai-compat-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp-injector.openai-compat :as openai]))

;; ═══════════════════════════════════════════════════════════════
;; Predicate tests
;; ═══════════════════════════════════════════════════════════════

(deftest o-series-detection-test
  (testing "O-series predicate correctly identifies OpenAI reasoning models"
    (is (openai/o-series? "o1"))
    (is (openai/o-series? "o1-mini"))
    (is (openai/o-series? "o1-preview"))
    (is (openai/o-series? "o3"))
    (is (openai/o-series? "o3-mini"))
    (is (openai/o-series? "o-2024-12-17"))
    (is (not (openai/o-series? "gpt-4o")))
    (is (not (openai/o-series? "gpt-4o-mini")))
    (is (not (openai/o-series? "claude-3-opus")))
    (is (not (openai/o-series? "qwen3-235b")))
    (is (not (openai/o-series? nil)))))

(deftest o1-preview-detection-test
  (testing "o1-preview predicate correctly identifies o1-preview models"
    (is (openai/o1-preview? "o1-preview"))
    (is (openai/o1-preview? "o1-preview-2024-09-12"))
    (is (not (openai/o1-preview? "o1")))
    (is (not (openai/o1-preview? "o1-mini")))
    (is (not (openai/o1-preview? "gpt-4o")))))

;; ═══════════════════════════════════════════════════════════════
;; O-series normalization tests
;; Note: keys use underscore format (matching parse-body output),
;; NOT kebab-case (which parse-chat-request would produce but is dead code)
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
      ;; Client's explicit max_completion_tokens should be preserved
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
;; Loop pinning tests
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-loop-pinning-test
  (testing "Loop pinning applies temperature and effort during tool iterations"
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

;; ═══════════════════════════════════════════════════════════════
;; o1-preview param stripping tests
;; ═══════════════════════════════════════════════════════════════

(deftest normalize-request-o1-preview-strips-params-test
  (testing "o1-preview strips unsupported parameters"
    (let [request {:model "o1-preview-2024-09-12"
                   :messages [{:role "system" :content "You are a helpful assistant."}]
                   :max_tokens 1000
                   :temperature 0.5
                   :top_p 0.9
                   :frequency_penalty 0.1
                   :presence_penalty 0.1}
          normalized (openai/normalize-request request)]
      (is (= "developer" (:role (first (:messages normalized)))))
      (is (= 1000 (:max_completion_tokens normalized)))
      (is (nil? (:temperature normalized)))
      (is (nil? (:top_p normalized)))
      (is (nil? (:frequency_penalty normalized)))
      (is (nil? (:presence_penalty normalized))))))

(deftest normalize-request-o1-preview-loop-pinning-order-test
  (testing "o1-preview: stripping happens first, then loop pinning overwrites"
    (let [request {:model "o1-preview-2024-09-12"
                   :messages [{:role "user" :content "Hello"}]
                   :temperature 0.7
                   :top_p 0.9}
          normalized (openai/normalize-request request
                                               {:loop-pinning true
                                                :pin-temp 0.1
                                                :pin-effort :low
                                                :iteration 1})]
      ;; Stripping removes original temperature, then pinning sets it to 0.1
      (is (= 0.1 (:temperature normalized)))
      (is (= :low (:reasoning_effort normalized)))
      ;; top_p was stripped and not restored by pinning
      (is (nil? (:top_p normalized))))))

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
