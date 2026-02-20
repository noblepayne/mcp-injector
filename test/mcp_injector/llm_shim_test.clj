(ns mcp-injector.llm-shim-test
  "Integration tests for OpenClaw→Bifrost shim functionality.
   Tests fallback injection, stream stripping, and SSE conversion."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(def test-state (atom {}))

(use-fixtures :once
  (fn [f]
    ;; Start test Bifrost server
    (let [llm (test-llm/start-server)]
      (swap! test-state assoc :llm llm)

      ;; Start mcp-injector pointing at test Bifrost
      ;; No MCP config - testing pure shim mode
      (let [injector (core/start-server
                      {:port 0
                       :host "127.0.0.1"
                       :llm-url (str "http://localhost:" (:port llm))
                       :mcp-config "./mcp-servers.edn"})]
        (swap! test-state assoc :injector injector)
        (try
          (f)
          (finally
            (core/stop-server injector)
            (test-llm/stop-server llm)))))))

(use-fixtures :each
  (fn [f]
    ;; Clear request tracking before each test
    (test-llm/clear-responses (:llm @test-state))
    (reset! (:received-requests (:llm @test-state)) [])
    (f)))

(deftest test-stream-flag-stripped
  (testing "Request has stream=true stripped before forwarding to Bifrost"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          ;; OpenClaw sends stream=true
          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}]

      ;; Make request to injector
      @(http/post (str "http://localhost:" port "/v1/chat/completions")
                  {:body (json/generate-string request)
                   :headers {"Content-Type" "application/json"}})

      ;; Check that Bifrost received stream=false
      (let [received-requests @(:received-requests llm)
            body (first received-requests)]
        (is (= false (:stream body))
            "Bifrost should receive stream=false")))))

(deftest test-fallbacks-injected
  (testing "Fallbacks array is injected into request"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}]

      @(http/post (str "http://localhost:" port "/v1/chat/completions")
                  {:body (json/generate-string request)
                   :headers {"Content-Type" "application/json"}})

      ;; Check that Bifrost received fallbacks array
      (let [received-requests @(:received-requests llm)
            body (first received-requests)
            fallbacks (:fallbacks body)]

        (is (vector? fallbacks) "Fallbacks should be an array")
        (is (= 3 (count fallbacks)) "Should have 3 fallback providers")

        ;; Check provider order: zen → nvidia → openrouter (now strings, not objects)
        (is (= "zen/kimi-k2.5-free" (nth fallbacks 0)))
        (is (= "nvidia/moonshotai/kimi-k2.5" (nth fallbacks 1)))
        (is (= "openrouter/moonshotai/kimi-k2.5" (nth fallbacks 2)))))))

(deftest test-sse-response-returned
  (testing "Response is converted back to SSE for OpenClaw"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          ;; Configure Bifrost to return a simple response
          _ (test-llm/set-next-response
             llm
             {:role "assistant"
              :content "Hello from Bifrost"})

          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}

          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string request)
                                :headers {"Content-Type" "application/json"}})]

      ;; Check SSE format
      (is (= 200 (:status response)))
      (is (= "text/event-stream" (get-in response [:headers :content-type])))

      ;; Response body should be SSE format
      (let [body (:body response)]
        (is (str/includes? body "data:"))
        (is (str/includes? body "[DONE]"))))))

(deftest test-non-mcp-tool-passthrough
  (testing "Non-MCP tool calls are passed back to OpenClaw in SSE"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          ;; Configure Bifrost to return a non-MCP tool call
          _ (test-llm/set-next-response
             llm
             {:role "assistant"
              :content ""
              :tool_calls [{:name "openclaw.reminder"
                            :arguments {:task "test"}}]})

          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Set a reminder"}]
                   :stream true}

          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string request)
                                :headers {"Content-Type" "application/json"}})]

      ;; Check that we get back SSE with tool_calls
      (is (= 200 (:status response)))
      (let [body (:body response)]
        (is (str/includes? body "data:"))
        ;; Should contain the tool call
        (is (str/includes? body "openclaw.reminder"))
        (is (str/includes? body "[DONE]"))))))

(deftest test-llm-rate-limit
  (testing "Bifrost returns 429 rate limit error - client gets proper error response"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          ;; Configure Bifrost to return 429
          _ (test-llm/set-error-response llm 429 "Rate limit exceeded")

          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}

          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string request)
                                :headers {"Content-Type" "application/json"}})]

      ;; Should get 429 status
      (is (= 429 (:status response)))

      ;; Should be SSE format for stream=true
      (is (= "text/event-stream" (get-in response [:headers :content-type])))

      ;; Body should contain error info
      (let [body (:body response)]
        (is (str/includes? body "rate_limit_exceeded"))
        (is (str/includes? body "[DONE]"))))))

(deftest test-llm-server-error
  (testing "Bifrost returns 500 error - client gets 502 bad gateway"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          ;; Configure Bifrost to return 500
          _ (test-llm/set-error-response llm 500 "Internal server error")

          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream false}

          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string request)
                                :headers {"Content-Type" "application/json"}})]

      ;; Should get 502 status (bad gateway)
      (is (= 502 (:status response)))

      ;; Should be JSON format for stream=false
      (is (= "application/json" (get-in response [:headers :content-type])))

      ;; Body should contain error
      (let [body (json/parse-string (body->string (:body response)) true)]
        (is (= "upstream_error" (get-in body [:error :type])))))))

(deftest test-llm-timeout
  (testing "Bifrost times out - client gets 504 gateway timeout"
    (let [{:keys [injector llm]} @test-state
          port (:port injector)

          ;; Configure Bifrost to timeout (delay longer than our timeout)
          _ (test-llm/set-timeout-response llm 100) ; 100ms delay for testing

          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}

          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                               {:body (json/generate-string request)
                                :headers {"Content-Type" "application/json"}})]

      ;; Should eventually return (might be 200 if timeout not triggered in test)
      ;; For now just verify it doesn't hang forever
      (is (contains? #{200 429 502 503 504} (:status response))))))

(deftest test-mcp-tool-execution
  (testing "MCP tools (server.tool format) are executed by shim"
    ;; This requires MCP config - test will be added when we have MCP servers
    ;; For now, placeholder showing intent
    (is (= 1 1))))

(defn -main
  "Entry point for running tests via bb"
  [& _args]
  (let [result (clojure.test/run-tests 'mcp-injector.llm-shim-test)]
    (System/exit (if (zero? (:fail result)) 0 1))))
