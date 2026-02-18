# mcp-injector Error Translation Plan

## Goal
Make mcp-injector robustly handle Bifrost's cryptic errors (especially context overflow) by translating them into OpenClaw-recognizable messages that trigger proper compaction/retry behavior.

## Background

### The Problem
- Bifrost returns: `"Cannot read properties of undefined (reading 'prompt_tokens')"` when upstream providers fail during context overflow
- This doesn't match OpenClaw's `isLikelyContextOverflowError()` patterns
- OpenClaw doesn't trigger compaction, may disable the shim provider

### OpenClaw's Context Overflow Detection (from `errors.ts`)
Matches:
- `"context window"`, `"context length"`, `"maximum context length"`
- `"request_too_large"`, `"request exceeds the maximum size"`
- `"context length exceeded"`, `"prompt is too long"`
- `"exceeds model context window"`
- `"context overflow:"`
- `"request size exceeds"` + context terms
- `"413"` + `"too large"`

**Key behavior**: When matched, OpenClaw runs compaction/retry instead of model fallback

## Implementation

### Phase 1: Error Detection & Translation Layer

**File**: `src/mcp_injector/core.clj`

#### 1.1 Add Context Overflow Detection (after line 14)
```clojure
(defn- is-context-overflow-error?
  "Detect context overflow errors from upstream providers through Bifrost.
   Covers JavaScript null errors and standard context overflow messages."
  [error-str]
  (when (string? error-str)
    (let [patterns [#"(?i)cannot read propert(?:y|ies) of undefined.*prompt"
                    #"(?i)cannot read propert(?:y|ies) of null.*prompt"  
                    #"(?i)prompt_tokens.*undefined"
                    #"(?i)prompt_tokens.*null"
                    #"(?i)context window.*exceeded"
                    #"(?i)context length.*exceeded"
                    #"(?i)maximum context.*exceeded"
                    #"(?i)request.*too large"
                    #"(?i)prompt is too long"
                    #"(?i)exceeds model context"
                    #"(?i)413.*too large"
                    #"(?i)request size exceeds"]]
      (some #(re-find % error-str) patterns))))
```

#### 1.2 Add Error Translation Function
```clojure
(defn- translate-error-for-openclaw
  "Translate upstream errors into OpenClaw-recognizable format.
   
   Returns map with:
   - :message - User-friendly message for OpenClaw
   - :status - HTTP status code (503 for retryable context overflow)
   - :original - Original error for logging (optional)"
  [error-data status-code]
  (let [error-str (or (get-in error-data [:error :message])
                      (:message error-data)
                      (:details error-data)
                      (str error-data))]
    (if (is-context-overflow-error? error-str)
      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
       :status 503  ; Service Unavailable = retryable
       :type "context_overflow"}
      {:message (or (:message error-data) "Upstream error")
       :status status-code
       :type "upstream_error"})))
```

#### 1.3 Update Server Error Handler (lines 112-129)
Current code returns 502 for all server errors. Update to:
- Check if error indicates context overflow
- If yes: return 503 with translated message
- If no: return 502 as before
- Always log original error for debugging

#### 1.4 Update Connection Error Handler (lines 151-158)
Same translation logic for connection errors that might contain overflow patterns

#### 1.5 Update Virtual Model Chain Handler (lines 234-238)
Apply same translation when all providers fail

### Phase 2: Enhanced Logging with Upstream Details

#### 2.1 Capture Upstream Request/Response
When Bifrost exposes real upstream requests (if enabled), capture in logs:
- Original provider error (before Bifrost wrapping)
- Request token count (if available)
- Model that failed

Add to `call-llm` function logging:
```clojure
(log-request "debug" "LLM call details"
             {:url llm-url
              :model (:model request-body)
              :message-count (count (:messages request-body))
              :has-tools (boolean (seq (:tools request-body)))})
```

#### 2.2 Error Context Logging
When translating errors, log both original and translated:
```clojure
(log-request "warn" "Translating error for OpenClaw"
             {:original-error error-str
              :translated-message (:message translated)
              :status (:status translated)})
```

### Phase 3: Testing

#### 3.1 Unit Tests
Add tests in `test/mcp_injector/integration_test.clj`:

1. **Bifrost JS error translation**
   - Input: `"Cannot read properties of undefined (reading 'prompt_tokens')"`
   - Expected: Status 503, message contains "Context overflow"

2. **Standard context overflow passthrough**
   - Input: `"context window exceeded"`
   - Expected: Status 503, message preserved

3. **Normal 500 error**
   - Input: `"Internal server error"`
   - Expected: Status 502, unchanged

4. **Rate limit unchanged**
   - Input: Status 429
   - Expected: Status 429, unchanged

#### 3.2 Integration Test
Test with mock Bifrost server returning various error patterns

## Status Tracking

- [x] Phase 1.1: Add `is-context-overflow-error?` function
- [x] Phase 1.2: Add `translate-error-for-openclaw` function  
- [x] Phase 1.3: Update server error handler (500s)
- [x] Phase 1.4: Update connection error handler
- [x] Phase 1.5: Update virtual model chain handler
- [x] Phase 2.1: Enhanced request logging (added message count and content length)
- [x] Phase 2.2: Error translation logging (added in all error handlers)
- [x] Phase 3: Tests
- [x] Phase 4: Verification (tests, lint, format all pass)

## Summary

Successfully implemented robust error translation layer in mcp-injector to handle Bifrost's cryptic context overflow errors, plus enhanced observability using Bifrost's extra_fields.

### Phase 1: Error Translation (Commit 4bbcf56)

1. **Error Detection**: Added `is-context-overflow-error?` function that detects:
   - Bifrost JS errors: "Cannot read properties of undefined (reading 'prompt_tokens')"
   - Standard context overflow patterns: "context window exceeded", "prompt is too long", etc.

2. **Error Translation**: Added `translate-error-for-openclaw` function that:
   - Returns HTTP 503 (retryable) for context overflows
   - Returns HTTP 502 for other upstream errors
   - Provides OpenClaw-compatible error messages

3. **Enhanced Logging**: 
   - Logs original error for debugging
   - Logs translated error and detection result
   - Added request metadata (message count, content length)

4. **Comprehensive Tests**: Added 4 new test cases:
   - Bifrost JS error translation → 503
   - Standard context overflow detection → 503
   - Normal 500 errors unchanged → 502
   - Rate limits unchanged → 429

### Phase 2: Bifrost extra_fields Integration (Commit 6308127)

Leveraged Bifrost's new `extra_fields` to extract upstream provider information:

1. **Provider/Model Extraction**: Capture from `extra_fields.provider` and `extra_fields.model_requested`
2. **Upstream Error Detection**: Check `extra_fields.raw_response.error` for real upstream error
3. **Enhanced Logging**: 
   - Log which provider actually served the request
   - Log upstream error separately from Bifrost wrapper
4. **Client Context**: Include provider/model in error responses

### Test Results
```
Ran 12 tests containing 36 assertions.
0 failures, 0 errors.
```

### Key Design Decisions

- **HTTP 503 vs 502**: 503 signals "Service Unavailable" which is retryable, triggering OpenClaw's compaction/retry logic instead of disabling the provider
- **Message format**: Matches OpenClaw's exact format for context overflow errors
- **Defensive patterns**: Case-insensitive regex covers variations of error messages
- **Logging**: Original errors preserved in logs for debugging while translated errors sent to client
- **Extra fields**: Extract upstream error from Bifrost's `raw_response.error` for better detection

### Bifrost extra_fields Structure

```json
{
  "extra_fields": {
    "provider": "nvidia",
    "model_requested": "moonshotai/kimi-k2-thinking",
    "raw_response": {
      "error": {"message": "context window exceeded", ...}
    }
  }
}
```

### Files Modified

- `src/mcp_injector/core.clj`: Added error detection, translation, extra_fields parsing, enhanced logging
- `test/mcp_injector/integration_test.clj`: Added 4 new test cases
- `PLAN.md`: This plan document

All changes pass tests, linting, and formatting checks.
- [ ] Phase 4: Verification (run tests, lint, format)

## Design Decisions

1. **HTTP 503 vs 502**: Use 503 (Service Unavailable) for context overflow because it's retryable. OpenClaw will retry with compaction instead of disabling the provider.

2. **Message format**: Match OpenClaw's exact `formatAssistantErrorText` output for context overflows: `"Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."`

3. **Pattern matching**: Use case-insensitive regex to catch variations (e.g., "Cannot read property of null" vs "Cannot read properties of undefined")

4. **Logging**: Always log original error at debug level, translated error at warn level

## Future Enhancements (Out of Scope)

- Metrics on error types
- Circuit breaker for repeated context overflows
- Automatic retry with smaller context

## Notes

- Keep `TODO.md` item about 502 handling - this addresses it
- Bifrost's real request exposure (if enabled) helps debug but shouldn't change our translation logic
- Goal is to be defensive: when in doubt, translate to context overflow rather than let OpenClaw disable us
