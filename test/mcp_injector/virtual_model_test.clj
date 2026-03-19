(ns mcp-injector.virtual-model-test
  "Integration tests for virtual model fallback with cooldown system."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(defn- strip-footer [content]
  (if (string? content)
    (str/replace content #"\n\n<!-- x-injector-v1\n[\s\S]*?-->" "")
    content))

(def test-state (atom {}))

(use-fixtures :once
  (fn [t]
    (let [llm (test-llm/start-server)
          _ (System/setProperty "INJECTOR_HMAC_SECRET" "test-secret-at-least-32-chars-long-12345")
          injector (core/start-server {:port 0
                                       :host "127.0.0.1"
                                       :llm-url (str "http://localhost:" (:port llm))
                                       :mcp-servers {:llm-gateway {:url (str "http://localhost:" (:port llm))
                                                                   :virtual-models {:brain {:chain ["provider1" "provider2"]
                                                                                            :cooldown-minutes 1}}
                                                                   :fallbacks []}}})]
      (reset! test-state {:llm llm :injector injector})
      (try (t)
           (finally
             (core/stop-server injector)
             (test-llm/stop-server llm))))))

(use-fixtures :each
  (fn [t]
    (test-llm/clear-responses (:llm @test-state))
    (core/reset-usage-stats!)
    (core/reset-cooldowns!)
    (t)))

(deftest test-pass-through-non-virtual
  (testing "Requests for non-virtual models pass through directly"
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "Direct response"})
    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "gpt-4" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= "Direct response" (strip-footer (get-in body [:choices 0 :message :content])))))))

(deftest test-virtual-model-success-first-try
  (testing "Virtual model succeeds on first provider in chain"
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "Success!"})
    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= "Success!" (strip-footer (get-in body [:choices 0 :message :content])))))))

(deftest test-virtual-model-fallback-on-429
  (testing "Virtual model falls back to second provider on 429"
    (test-llm/set-error-response (:llm @test-state) 429 "Rate limited")
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "Fallback!"})
    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= "Fallback!" (strip-footer (get-in body [:choices 0 :message :content])))))))

(deftest test-virtual-model-all-providers-fail
  (testing "Virtual model fails with 502 if all providers in chain fail"
    (test-llm/set-error-response (:llm @test-state) 429 "Rate limited 1")
    (test-llm/set-error-response (:llm @test-state) 429 "Rate limited 2")
    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 502 (:status response)))
      (is (str/includes? (get-in body [:error :message]) "All providers failed")))))

(deftest test-virtual-model-cooldown-persists
  (testing "Failing provider stays on cooldown"
    (test-llm/set-error-response (:llm @test-state) 429 "Rate limited")
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "Fallback response"})

    ;; First call triggers fallback and cooldown
    @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})

    (test-llm/clear-responses (:llm @test-state))
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "Immediate success"})

    ;; Second call should hit provider 2 immediately because provider 1 is on cooldown
    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= "Immediate success" (strip-footer (get-in body [:choices 0 :message :content]))))
      ;; Verify LLM only received one request (provider 2)
      (is (= 1 (count @(:received-requests (:llm @test-state))))))))

(deftest test-usage-tracking-in-fallback
  (testing "Usage stats are aggregated across fallback attempts"
    (test-llm/set-error-response (:llm @test-state) 429 "Rate limited")
    (test-llm/set-response-with-usage (:llm @test-state)
                                      {:role "assistant" :content "Success!"}
                                      {:prompt_tokens 50 :completion_tokens 10 :total_tokens 60})

    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= 50 (get-in body [:usage :prompt_tokens])))

      ;; Verify global stats endpoint too
      (let [stats-resp @(http/get (str "http://localhost:" (:port (:injector @test-state)) "/api/v1/stats"))
            stats (json/parse-string (body->string (:body stats-resp)) true)]
        (is (= 1 (get-in stats [:stats :provider2 :requests])))
        (is (= 1 (get-in stats [:stats :provider1 :rate-limits])))))))

(deftest test-stream-true-converted-to-false
  (testing "Virtual models force stream:false when calling providers"
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "ok"})
    @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}] :stream true})})
    (let [last-req (last @(:received-requests (:llm @test-state)))]
      (is (false? (:stream last-req))))))

(deftest test-stream-options-removed-when-stream-false
  (testing "stream_options are removed when stream is false"
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "ok"})
    @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}] :stream_options {:include_usage true}})})
    (let [last-req (last @(:received-requests (:llm @test-state)))]
      (is (not (contains? last-req :stream_options))))))

(deftest test-virtual-model-response-scrubbing
  (testing "Virtual model responses are scrubbed for PII"
    (test-llm/set-next-response (:llm @test-state) {:role "assistant" :content "The email is wes@example.com"})
    (let [response @(http/post (str "http://localhost:" (:port (:injector @test-state)) "/v1/chat/completions")
                               {:body (json/generate-string {:model "brain" :messages [{:role "user" :content "hi"}]})})
          body (json/parse-string (body->string (:body response)) true)
          content (get-in body [:choices 0 :message :content])]
      (is (not (str/includes? content "wes@example.com")))
      (is (re-find #"\[EMAIL_ADDRESS_[a-f0-9]+\]" content)))))

(defn -main [& _args]
  (clojure.test/run-tests 'mcp-injector.virtual-model-test))
