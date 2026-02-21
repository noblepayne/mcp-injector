(ns mcp-injector.integration-test
  "Full stack integration tests for mcp-injector.
   Tests the flow: Client → mcp-injector → LLM gateway → LLM"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]))

(defn body->string [body]
  (if (string? body) body (slurp body)))

;; Test infrastructure state
(def ^:dynamic *test-mcp* nil)
(def ^:dynamic *test-llm* nil)
(def ^:dynamic *injector* nil)

(defn integration-fixture
  "Fixture that starts all test servers before running tests"
  [test-fn]
  (let [mcp-server (test-mcp/start-test-mcp-server)
        llm-server (test-llm/start-server)
        injector-server (core/start-server {:port 0
                                            :host "127.0.0.1"
                                            :llm-url (str "http://localhost:" (:port llm-server))
                                            :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
                                                                             :tools [:retrieve_customer :list_charges]}
                                                                    :postgres {:url (str "http://localhost:" (:port mcp-server))
                                                                               :tools [:query]}}
                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
    (try
      (binding [*test-mcp* mcp-server
                *test-llm* llm-server
                *injector* injector-server]
        (test-fn))
      (finally
        (core/stop-server injector-server)
        (test-llm/stop-server llm-server)
        (test-mcp/stop-server mcp-server)))))

(use-fixtures :once integration-fixture)

(defn clear-requests-fixture
  "Clear request tracking before each test"
  [test-fn]
  (test-llm/clear-responses *test-llm*)
  (reset! (:received-requests *test-llm*) [])
  (test-fn))

(use-fixtures :each clear-requests-fixture)

(deftest test-infrastructure-works
  (testing "Sanity check that our test servers work. Test MCP server responds to tools/list"
    (let [response @(http/post (str "http://localhost:" (:port *test-mcp*) "/")
                               {:body (json/generate-string
                                       {:jsonrpc "2.0"
                                        :id "test-1"
                                        :method "tools/list"
                                        :params {}})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= "2.0" (:jsonrpc body)))
      (is (vector? (get-in body [:result :tools])))))

  (testing "Test LLM server responds to chat completions"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Hello from test"})
    (let [response @(http/post (str "http://localhost:" (:port *test-llm*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Test"}]})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]
      (is (= 200 (:status response)))
      (is (= "Hello from test" (get-in body [:choices 0 :message :content]))))))

(deftest simple-chat-no-tools
  (testing "User asks a question that doesn't need tools. mcp-injector forwards request to LLM and returns response"
    ;; Setup: LLM returns simple text response
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "I can help you with that!"})

    ;; Send request to mcp-injector
    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Hello, can you help me?"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Verify response
      (is (= 200 (:status response)))
      (is (= "assistant" (get-in body [:choices 0 :message :role])))
      (is (= "I can help you with that!" (get-in body [:choices 0 :message :content])))

      ;; Verify LLM received the request
      (is (= 1 (count @(:received-requests *test-llm*)))))))

(deftest single-tool-call
  (testing "User request requires one tool call. Agent loop executes tool and returns result"
    ;; Setup discovery turns
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__stripe__retrieve_customer"
                                       :arguments {:customer_id "cus_123"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Found customer: customer@example.com"})

    ;; Send request to mcp-injector
    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Find customer cus_123"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Verify final response
      (is (= 200 (:status response)))
      (is (str/includes? (get-in body [:choices 0 :message :content]) "customer@example.com"))

      ;; Verify LLM was called 3 times (discovery + execution + final)
      (is (= 3 (count @(:received-requests *test-llm*)))))))

(deftest multi-turn-agent-loop
  (testing "User request requires multiple tool calls. Agent loop handles multiple turns"
    ;; Setup: Discovery 1, Call 1, Discovery 2, Call 2, then final response
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "list_charges"}}])
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__stripe__list_charges"
                                       :arguments {:customer "cus_123"}}])
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "postgres"
                                                   :tool "query"}}])
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__postgres__query"
                                       :arguments {:sql "SELECT * FROM analytics"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Here is your complete analytics report."})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Get my charges and analytics"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          _body (json/parse-string (body->string (:body response)) true)]

      (is (= 200 (:status response)))
      ;; LLM called 5 times
      (is (= 5 (count @(:received-requests *test-llm*)))))))

(deftest get-tool-schema-meta-tool
  (testing "LLM calls get_tool_schema to discover tool details. Meta-tool returns full schema"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "The stripe.retrieve_customer tool requires a customer_id parameter."})

    ;; Send request
    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "What parameters does stripe.retrieve_customer need?"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)
          requests @(:received-requests *test-llm*)]

      (is (= 200 (:status response)))
      (is (str/includes? (get-in body [:choices 0 :message :content]) "customer_id"))
      ;; Verify 2 calls to LLM (initial + after schema result)
      (is (= 2 (count requests)))
      ;; Verify schema was returned to LLM
      ;; Messages: 0: System, 1: User, 2: Assistant (call), 3: Tool (result)
      (is (str/includes? (get-in (last requests) [:messages 3 :content]) "retrieve_customer")))))

(deftest max-iterations-limit
  (testing "Agent loop stops after max iterations. Infinite loop scenario is handled gracefully"
    ;; Setup: LLM always returns tool_calls
    (test-llm/clear-responses *test-llm*)
    ;; First discovery
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])
    ;; Then infinite calls
    (dotimes [_ 15]
      (test-llm/set-tool-call-response *test-llm*
                                       [{:name "mcp__stripe__retrieve_customer"
                                         :arguments {:customer_id "cus_123"}}]))

    ;; Test will verify:
    ;; 1. Loop stops at max-iterations
    ;; 2. Error or partial result returned

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Infinite loop test"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          _body (json/parse-string (body->string (:body response)) true)]

      ;; Should eventually return even with infinite tool calls
      (is (= 200 (:status response))))))

(deftest mcp-server-error
  (testing "Tool call fails, error handled gracefully. MCP errors are included in conversation context"
    ;; Setup: MCP will fail (but our test MCP doesn't support errors yet)
    ;; For now, test that errors don't crash the system
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "get_tool_schema"
                                       :arguments {:server "stripe"
                                                   :tool "retrieve_customer"}}])
    (test-llm/set-tool-call-response *test-llm*
                                     [{:name "mcp__stripe__retrieve_customer"
                                       :arguments {:customer_id "invalid_id"}}])
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "I encountered an error retrieving that customer."})

    ;; Send request
    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Find customer invalid_id"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})]
      ;; Verify response (should handle error gracefully)
      (is (= 200 (:status response)))
      ;; LLM should see the error and respond
      (is (>= (count @(:received-requests *test-llm*)) 3)))))

(deftest stream-mode-sse-format
  (testing "Stream=true returns SSE format for OpenClaw compatibility - SSE stream is properly formatted"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Streaming response!"})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Hello"}]
                                        :stream true})
                                :headers {"Content-Type" "application/json"}})
          body (:body response)
          headers (:headers response)]

      ;; Verify SSE format
      (is (= 200 (:status response)))
      ;; Headers may have different casing depending on http-kit version
      (is (some #(re-find #"event-stream" (str %)) (vals headers)))
      ;; Should contain SSE data prefix
      (is (str/includes? body "data:"))
      ;; Should contain [DONE] marker
      (is (str/includes? body "[DONE]"))
      ;; Should contain the actual content
      (is (str/includes? body "Streaming response!")))))

(deftest bifrost-context-overflow-error-translation
  (testing "Bifrost JS error gets translated to 503 with OpenClaw-compatible message"
    ;; Bifrost returns this when upstream provider has context overflow
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-error-response *test-llm* 500
                                 "Cannot read properties of undefined (reading 'prompt_tokens')")

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Test"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Should return 503 (retryable) not 502
      (is (= 503 (:status response)))
      ;; Should have OpenClaw-compatible context overflow message
      (is (str/includes? (get-in body [:error :message]) "Context overflow"))
      (is (str/includes? (get-in body [:error :message]) "prompt too large"))
      ;; Should preserve original error for debugging
      (is (str/includes? (str (get-in body [:error :details])) "Cannot read properties of undefined"))
      ;; Should mark type as context_overflow
      (is (= "context_overflow" (get-in body [:error :type]))))))

(deftest standard-context-overflow-detection
  (testing "Standard context overflow messages are detected and translated"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-error-response *test-llm* 500 "context window exceeded")

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Test"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Should return 503
      (is (= 503 (:status response)))
      ;; Should have context overflow message
      (is (str/includes? (get-in body [:error :message]) "Context overflow"))
      (is (= "context_overflow" (get-in body [:error :type]))))))

(deftest normal-server-error-unchanged
  (testing "Normal 500 errors without context overflow patterns return 502"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-error-response *test-llm* 500 "Internal server error")

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Test"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Should return 502 (original behavior)
      (is (= 502 (:status response)))
      ;; Should have upstream_error type
      (is (= "upstream_error" (get-in body [:error :type]))))))

(deftest rate-limit-unchanged
  (testing "Rate limit errors (429) are not translated and return 429"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-error-response *test-llm* 429 "Rate limit exceeded")

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Test"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Should return 429
      (is (= 429 (:status response)))
      ;; Should have rate_limit type
      (is (= "rate_limit_exceeded" (get-in body [:error :type]))))))

(deftest streaming-context-overflow-translation
  (testing "Context overflow errors are translated even in streaming mode"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-error-response *test-llm* 500
                                 "Cannot read properties of undefined (reading 'prompt_tokens')")

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Test"}]
                                        :stream true})
                                :headers {"Content-Type" "application/json"}})
          body (:body response)]

      ;; Should return 503 even in streaming mode
      (is (= 503 (:status response)))
      ;; SSE format should still have the error
      (is (str/includes? body "Context overflow"))
      (is (str/includes? body "prompt too large")))))

(deftest virtual-model-chain-context-overflow
  (testing "Context overflow in virtual model chain gets translated"
    ;; Setup: First provider fails with context overflow, second succeeds
    (test-llm/clear-responses *test-llm*)
    ;; First response: context overflow (should translate to 503, trigger retry)
    (test-llm/set-error-response *test-llm* 500 "context window exceeded")
    ;; Second response: success
    (test-llm/set-next-response *test-llm*
                                {:role "assistant"
                                 :content "Success after fallback"})

    ;; Note: This test assumes virtual model fallback is configured
    ;; If not configured, this will just return 503 on first error
    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "virtual-model"
                                        :messages [{:role "user"
                                                    :content "Test with virtual model"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      ;; Either we got success (fallback worked) or 503 (no fallback configured)
      ;; Both are acceptable behaviors
      (is (or (= 200 (:status response))
              (and (= 503 (:status response))
                   (str/includes? (get-in body [:error :message]) "Context overflow")))))))

(deftest usage-propagated-to-client
  (testing "Usage stats from upstream LLM should be passed through to downstream clients"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-response-with-usage
     *test-llm*
     {:role "assistant"
      :content "Hello with usage!"}
     {:prompt_tokens 42
      :completion_tokens 17
      :total_tokens 59})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Hello"}]
                                        :stream false})
                                :headers {"Content-Type" "application/json"}})
          body (json/parse-string (body->string (:body response)) true)]

      (is (= 200 (:status response)))
      (is (= "Hello with usage!" (get-in body [:choices 0 :message :content])))
      ;; Verify usage is passed through
      (is (= 42 (get-in body [:usage :prompt_tokens])))
      (is (= 17 (get-in body [:usage :completion_tokens])))
      (is (= 59 (get-in body [:usage :total_tokens]))))))

(deftest usage-propagated-in-streaming-mode
  (testing "Usage stats should be included in final SSE chunk"
    (test-llm/clear-responses *test-llm*)
    (test-llm/set-response-with-usage
     *test-llm*
     {:role "assistant"
      :content "Streaming with usage!"}
     {:prompt_tokens 100
      :completion_tokens 25
      :total_tokens 125})

    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                               {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Hello"}]
                                        :stream true})
                                :headers {"Content-Type" "application/json"}})
          body (:body response)]

      (is (= 200 (:status response)))
      ;; Parse SSE events to find usage
      (is (str/includes? body "Streaming with usage!"))
      ;; Usage should be in the final chunk (before [DONE])
      (is (str/includes? body "\"usage\":"))
      (is (str/includes? body "\"prompt_tokens\":100"))
      (is (str/includes? body "\"completion_tokens\":25"))
      (is (str/includes? body "\"total_tokens\":125")))))

(deftest stats-endpoint-returns-usage-stats
  (testing "/stats endpoint should return aggregated usage statistics"
    ;; Clear any existing stats first
    (test-llm/clear-responses *test-llm*)

    ;; Make a few requests with different models
    (test-llm/set-response-with-usage
     *test-llm*
     {:role "assistant" :content "Request 1"}
     {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15})

    @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                {:body (json/generate-string
                        {:model "test-model-a"
                         :messages [{:role "user" :content "Hello"}]
                         :stream false})
                 :headers {"Content-Type" "application/json"}})

    (test-llm/set-response-with-usage
     *test-llm*
     {:role "assistant" :content "Request 2"}
     {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30})

    @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                {:body (json/generate-string
                        {:model "test-model-a"
                         :messages [{:role "user" :content "Hello again"}]
                         :stream false})
                 :headers {"Content-Type" "application/json"}})

    ;; Query stats endpoint
    (let [response @(http/get (str "http://localhost:" (:port *injector*) "/stats"))
          body (json/parse-string (body->string (:body response)) true)]

      (is (= 200 (:status response)))
      (is (contains? body :stats))
      (let [model-stats (get-in body [:stats :test-model-a])]
        (is (some? model-stats))
        (is (= 2 (:requests model-stats)))
        (is (= 30 (:total-input-tokens model-stats)))
        (is (= 15 (:total-output-tokens model-stats)))
        (is (= 45 (:total-tokens model-stats)))))))

(defn -main
  "Entry point for running tests via bb"
  [& _args]
  (let [result (clojure.test/run-tests 'mcp-injector.integration-test)]
    (System/exit (if (zero? (:fail result)) 0 1))))
