# Task: Minimal OpenClaw→Bifrost Shim with Fallback Injection

## Context

We have a working mcp-injector (Phase 1 complete) but need to solve an immediate problem:

- OpenClaw hardcodes `stream=true` in all requests
- Bifrost requires `fallbacks` array in request body for automatic failover
- When a provider returns 429, we want automatic fallback to next provider
- Goal: Free providers first (Zen, NVIDIA) → paid fallback (OpenRouter)

## What We're Building

A **minimal shim** that:

1. Accepts OpenAI-compatible requests from OpenClaw (with `stream=true`)
1. Strips `stream=true` → `stream=false` (Bifrost requirement)
1. Injects `fallbacks` array with provider chain
1. Forwards modified request to Bifrost
1. Converts Bifrost response back to SSE for OpenClaw

**Time budget:** 30-60 minutes (including tests)

## Bifrost Fallback Format

Bifrost expects a `fallbacks` array in the request body:

```json
{
  "model": "deepseek/deepseek-chat",
  "messages": [...],
  "stream": false,
  "fallbacks": [
    {
      "provider": "zen",
      "model": "deepseek/deepseek-chat"
    },
    {
      "provider": "nvidia", 
      "model": "deepseek/deepseek-chat"
    },
    {
      "provider": "openrouter",
      "model": "deepseek/deepseek-chat"
    }
  ]
}
```

**How it works:**

- Bifrost tries the main request first (based on Virtual Key weights)
- On 429 or error, tries first fallback
- If that fails, tries second fallback
- Only returns 429 to client if ALL providers exhausted

**For now:** Use `deepseek/deepseek-chat` (kimi2.5) across all providers since it's available on Zen, NVIDIA, and OpenRouter.

## Test-First Design (Our Style)

Follow the integration testing approach from AGENTS.md:

### 1. Write Test First

Create `test/mcp_injector/bifrost_shim_test.clj`:

```clojure
(ns mcp-injector.bifrost-shim-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [mcp-injector.test-bifrost-server :as test-bifrost]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(def test-state (atom {}))

(use-fixtures :once
  (fn [f]
    ;; Start test Bifrost server
    (let [bifrost (test-bifrost/start-test-bifrost-server)]
      (swap! test-state assoc :bifrost bifrost)
      
      ;; Start mcp-injector pointing at test Bifrost
      (let [injector (core/start-server 
                       {:port 0
                        :host "127.0.0.1"
                        :bifrost-url (str "http://localhost:" 
                                         (:port bifrost))})]
        (swap! test-state assoc :injector injector)
        (try
          (f)
          (finally
            ((:stop injector))
            ((:stop bifrost))))))))

(deftest test-stream-flag-stripped
  (testing "Request has stream=true stripped before forwarding to Bifrost"
    (let [{:keys [injector bifrost]} @test-state
          port (:port injector)
          
          ;; OpenClaw sends stream=true
          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}
          
          ;; Make request to injector
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                       {:body (json/generate-string request)
                        :headers {"Content-Type" "application/json"}})]
      
      ;; Check that Bifrost received stream=false
      (let [received-requests @(:received-requests bifrost)
            bifrost-request (first received-requests)
            body (json/parse-string (:body bifrost-request) true)]
        (is (= false (:stream body)) 
            "Bifrost should receive stream=false")))))

(deftest test-fallbacks-injected
  (testing "Fallbacks array is injected into request"
    (let [{:keys [injector bifrost]} @test-state
          port (:port injector)
          
          request {:model "deepseek/deepseek-chat"
                   :messages [{:role "user" :content "Hello"}]
                   :stream true}
          
          response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                       {:body (json/generate-string request)
                        :headers {"Content-Type" "application/json"}})]
      
      ;; Check that Bifrost received fallbacks array
      (let [received-requests @(:received-requests bifrost)
            bifrost-request (first received-requests)
            body (json/parse-string (:body bifrost-request) true)
            fallbacks (:fallbacks body)]
        
        (is (vector? fallbacks) "Fallbacks should be an array")
        (is (= 3 (count fallbacks)) "Should have 3 fallback providers")
        
        ;; Check provider order: zen → nvidia → openrouter
        (is (= "zen" (get-in fallbacks [0 :provider])))
        (is (= "nvidia" (get-in fallbacks [1 :provider])))
        (is (= "openrouter" (get-in fallbacks [2 :provider])))
        
        ;; All should use same model
        (is (every? #(= "deepseek/deepseek-chat" (:model %)) fallbacks))))))

(deftest test-sse-response-returned
  (testing "Response is converted back to SSE for OpenClaw"
    (let [{:keys [injector bifrost]} @test-state
          port (:port injector)
          
          ;; Configure Bifrost to return a simple response
          _ (test-bifrost/set-next-response 
              (:bifrost @test-state)
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
        (is (clojure.string/includes? body "data:"))
        (is (clojure.string/includes? body "[DONE]"))))))
```

### 2. Run Test (Should Fail)

```bash
bb test -n mcp-injector.bifrost-shim-test
```

Expected: Tests fail because functionality not implemented yet.

### 3. Implement Minimal Code

Update `src/mcp_injector/core.clj`:

```clojure
(def bifrost-fallbacks
  "Free-first fallback chain for Bifrost.
   Uses deepseek/deepseek-chat (kimi2.5) across all providers."
  [{:provider "zen"
    :model "deepseek/deepseek-chat"}
   {:provider "nvidia"
    :model "deepseek/deepseek-chat"}
   {:provider "openrouter"
    :model "deepseek/deepseek-chat"}])

(defn prepare-bifrost-request
  "Transform OpenClaw request for Bifrost compatibility.
   - Strips stream=true flag (Bifrost needs stream=false)
   - Injects fallbacks array for automatic provider failover"
  [openai-request]
  (-> openai-request
      (assoc :stream false)
      (assoc :fallbacks bifrost-fallbacks)))
```

Update `handle-chat-completion` function:

```clojure
(defn handle-chat-completion [request]
  (let [body (json/parse-string (slurp (:body request)) true)
        
        ;; Transform for Bifrost
        bifrost-req (prepare-bifrost-request body)
        
        ;; Forward to Bifrost
        bifrost-response @(http/post (get-bifrost-url)
                            {:body (json/generate-string bifrost-req)
                             :headers {"Content-Type" "application/json"}})]
    
    ;; Convert back to SSE for OpenClaw
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"}
     :body (openai-compat/to-sse-stream bifrost-response)}))
```

### 4. Run Test Again (Should Pass)

```bash
bb test -n mcp-injector.bifrost-shim-test
```

Expected: All 3 tests pass.

### 5. Test with Real Bifrost + OpenClaw

```bash
# Terminal 1: Start Bifrost (if not already running)
docker run -p 8080:8080 bifrost

# Terminal 2: Start mcp-injector
export MCP_INJECTOR_BIFROST_URL=http://localhost:8080/v1
bb run

# Terminal 3: Point OpenClaw at injector
export OPENAI_API_BASE=http://localhost:8080/v1
openclaw start

# Send a test message through OpenClaw
# Should see: Request hits Zen first, falls back to NVIDIA/OpenRouter on 429
```

## Success Criteria

- [ ] Tests pass: stream flag stripped
- [ ] Tests pass: fallbacks array injected correctly
- [ ] Tests pass: SSE response returned
- [ ] Manual test: OpenClaw → injector → Bifrost works
- [ ] Manual test: On 429 from first provider, falls back to next
- [ ] Code linted: `clj-kondo --lint src/ test/`
- [ ] Code formatted: `cljfmt fix src/ test/`

## Notes

- **Model choice:** Using `deepseek/deepseek-chat` because it's available on all three providers (Zen, NVIDIA, OpenRouter)
- **Phase 2 deferred:** Progressive MCP tool discovery is optimization for later
- **Keep it simple:** This is a 30-minute task, not a day-long project
- **Integration tests:** Follow the pattern from existing tests (real HTTP servers, no mocks)

## After This Works

Once tests pass and manual testing confirms it works:

1. Commit: `git commit -m "feat: add OpenClaw→Bifrost shim with fallback injection"`
1. Update `dev/current.edn` to mark task complete
1. **Move on to self-modifying agent work**

The injector is done enough. Phase 2 (progressive discovery) only needed if token costs become an issue.

## Questions?

- How to configure specific models per provider? → Can make `bifrost-fallbacks` configurable via EDN file later
- What if I want different fallback chains? → Can add multiple chains for different use cases later
- When to implement Phase 2? → Only if token costs from full tool schemas become a problem

For now: Keep it minimal, get it working, move on to the interesting work (self-modifying agent).
