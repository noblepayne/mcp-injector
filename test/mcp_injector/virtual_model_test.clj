(ns mcp-injector.virtual-model-test
  "Integration tests for virtual model fallback with cooldown system."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(def test-state (atom {}))

(use-fixtures :once
  (fn [f]
    (let [llm (test-llm/start-server)]
      (swap! test-state assoc :llm llm)
      (let [injector (core/start-server
                      {:port 0
                       :host "127.0.0.1"
                       :llm-url (str "http://localhost:" (:port llm))
                       :mcp-config "./mcp-servers.edn"
                       :virtual-models {"brain"
                                        {:chain ["provider1/model1"
                                                 "provider2/model2"]
                                         :cooldown-minutes 5
                                         :retry-on [429 500 503]}}})]
        (swap! test-state assoc :injector injector)
        (try
          (f)
          (finally
            (core/stop-server injector)
            (test-llm/stop-server llm)))))))

(use-fixtures :each
  (fn [f]
    (test-llm/clear-responses (:llm @test-state))
    (reset! (:received-requests (:llm @test-state)) [])
    (core/reset-cooldowns!)
    (f)))

(deftest test-pass-through-non-virtual
  (testing "Non-virtual model passes through directly"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-next-response llm
                                        {:role "assistant"
                                         :content "Direct response"})
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "zen/kimi-k2.5-free"
                                        :messages [{:role "user" :content "Hello"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Direct response" (get-in body [:choices 0 :message :content]))))))

(deftest test-virtual-model-success-first-try
  (testing "Virtual model succeeds on first provider"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-next-response llm
                                        {:role "assistant"
                                         :content "Success!"})
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "brain"
                                        :messages [{:role "user" :content "Hello"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Success!" (get-in body [:choices 0 :message :content]))))))

(deftest test-virtual-model-fallback-on-429
  (testing "Virtual model falls back on 429"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-error-response llm 429 "Rate limit")
          _ (test-llm/set-next-response llm
                                        {:role "assistant"
                                         :content "Fallback!"})
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "brain"
                                        :messages [{:role "user" :content "Hello"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Fallback!" (get-in body [:choices 0 :message :content]))))))

(deftest test-virtual-model-all-providers-fail
  (testing "Virtual model returns error when provider fails with non-retryable error"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-error-response llm 400 "Bad request")
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "brain"
                                        :messages [{:role "user" :content "Hello"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/generate-string (:body response) true)]
      (is (= 502 (:status response)))
      (is (re-find #"error" body)))))

(deftest test-virtual-model-cooldown-persists
  (testing "Failed provider stays in cooldown and is skipped on retry"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-error-response llm 429 "Rate limited")
          _ (test-llm/set-next-response llm
                                        {:role "assistant"
                                         :content "Fallback response"})
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "brain"
                                        :messages [{:role "user" :content "First"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (:body response) true)]
      (is (= 200 (:status response)))
      (is (= "Fallback response" (get-in body [:choices 0 :message :content])))

      (test-llm/clear-responses llm)
      (reset! (:received-requests llm) [])
      (test-llm/set-next-response llm {:role "assistant" :content "Second fallback"})

      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content "Second"}]
                                          :stream false})
                                  :headers {"Content-Type" "application/json"}})
            _ (json/parse-string (:body response) true)
            requests @(:received-requests llm)]
        (is (= 200 (:status response)))
        (is (= 1 (count requests)))
        (is (= "provider2/model2" (get-in (first requests) [:model])))))))

(deftest test-stream-true-converted-to-false
  (testing "stream=true from client is converted to stream=false for LLM"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-next-response llm
                                        {:role "assistant"
                                         :content "Success with stream=false"})
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "brain"
                                        :messages [{:role "user" :content "Hello"}]
                                        :stream true})
                                :headers {"Content-Type" "application/json"}})
          body (:body response)
          requests @(:received-requests llm)]
      (is (= 200 (:status response)))
      (is (= false (get-in (first requests) [:stream])))
      (is (str/starts-with? body "data:")))))

(deftest test-stream-options-removed-when-stream-false
  (testing "stream_options is removed when converting to stream=false"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)
          _ (test-llm/set-next-response llm
                                        {:role "assistant"
                                         :content "Success"})
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "brain"
                                        :messages [{:role "user" :content "Hi"}]
                                        :stream true
                                        :stream_options {:include_usage true}
                                        :max_tokens 100})
                                :headers {"Content-Type" "application/json"}})
          requests @(:received-requests llm)]
      (is (= 200 (:status response)))
      (is (nil? (get-in (first requests) [:stream_options])))
      (is (= false (get-in (first requests) [:stream]))))))

(defn -main
  "Entry point for running tests via bb"
  [& _args]
  (let [result (clojure.test/run-tests 'mcp-injector.virtual-model-test)]
    (System/exit (if (zero? (:fail result)) 0 1))))
