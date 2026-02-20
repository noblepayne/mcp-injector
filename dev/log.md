# Development Log

## 2026-02-14 - Session 006

### Flake Audit & NixOS Module Fixes

**Problem:** Flake had mismatched naming and env vars vs actual code.

**Issues Found:**

- Package named "situated-agent-runtime" but project is "mcp-injector"
- Env vars used `SAR_*` but code uses `MCP_INJECTOR_*`
- NixOS module option `bifrostUrl` vs code uses `llm-url`
- Referenced non-existent `skills/` directory
- Wrapper script didn't work (missing deps loading)

**Changes Made:**

1. **flake.nix** - Complete rewrite:

   - Renamed package to `mcp-injector`
   - Changed all env vars: `SAR_*` → `MCP_INJECTOR_*`
   - Fixed module options: `bifrostUrl` → `llm-url`, `skillsDir` removed
   - Fixed wrapper: uses `bb run serve` to properly load deps
   - Default port changed to 8088 (matches code default)

1. **NIX_USAGE.md** - Updated:

   - All naming and env vars now match actual implementation
   - Examples use correct `MCP_INJECTOR_*` vars
   - NixOS module examples updated

1. **Code lint fixes:**

   - Removed redundant `let` in `core.clj` (line 301)
   - Fixed unused `body` binding in `virtual_model_test.clj`

**Verification:**

- `nix build` - builds successfully
- `nix run` - server starts and responds on port 8088
- `bb test` - 8 tests, 24 assertions, 0 failures
- `clj-kondo` - lint passes (warnings only for dynamic requires)
- `cljfmt` - all files formatted correctly

______________________________________________________________________

## 2026-02-12 - Session 004

### New Work: Bifrost Shim Implementation

**Context:** Extending Phase 1 to add minimal OpenClaw→Bifrost shim with fallback injection.

**Problem we're solving:**

- OpenClaw hardcodes `stream=true`
- Bifrost requires `stream=false` + `fallbacks` array for automatic failover
- Want: Free providers first (Zen, NVIDIA) → paid fallback (OpenRouter)

**Design Decisions:**

1. **EDN-based config**: Extend mcp-servers.edn with `:bifrost` key containing fallbacks
1. **Model mapping**:
   - zen/kimi-k2.5-free
   - nvidia/moonshotai/kimi-k2.5
   - openrouter/moonshotai/kimi-k2.5 (paid)
1. **Conditional MCP**: Shim works without MCP config (pure pass-through)
1. **Tool routing**: MCP tools execute, non-MCP tools pass back to OpenClaw in SSE

**Flow:**

```
OpenClaw (stream=true)
    ↓
mcp-injector shim
    - Strip stream=true → stream=false
    - Inject fallbacks array
    - Optional: Inject MCP tools if configured
    ↓
Bifrost
    ↓
LLM Provider (with fallback chain)
    ↓
mcp-injector
    - Execute MCP tools if present
    - Pass non-MCP tool_calls back to OpenClaw
    - Convert to SSE
    ↓
OpenClaw
```

### Implementation Completed ✅

**All tasks completed:**

1. ✅ Created `test/mcp_injector/bifrost_shim_test.clj` (5 tests, all passing)

   - test-stream-flag-stripped
   - test-fallbacks-injected
   - test-sse-response-returned
   - test-non-mcp-tool-passthrough
   - test-mcp-tool-execution

1. ✅ Updated `mcp-servers.edn` with bifrost fallbacks config:

   ```clojure
   :bifrost
   {:fallbacks [{:provider "zen" :model "kimi-k2.5-free"}
                {:provider "nvidia" :model "moonshotai/kimi-k2.5"}
                {:provider "openrouter" :model "moonshotai/kimi-k2.5"}]}
   ```

1. ✅ Updated `config.clj`:

   - Added `get-bifrost-fallbacks` function

1. ✅ Updated `core.clj`:

   - `prepare-bifrost-request` - strips stream flag, injects fallbacks
   - `is-mcp-tool?` - checks if tool is MCP format
   - `process-tool-calls` - handles MCP execution vs pass-through
   - Dual mode: SSE for stream=true, JSON for stream=false

**Key Design Decisions:**

- Tool routing based on `server.tool` format - if server exists in MCP config, execute it; otherwise pass through
- Backward compatibility maintained - tests with stream=false still get JSON
- Clean separation: `process-tool-calls` function works for both streaming and non-streaming modes

**Test Results:**

- All 12 tests passing (7 original + 5 new)
- 32 total assertions
- Zero lint warnings
- All code formatted

**Files Modified:**

- `mcp-servers.edn`
- `src/mcp_injector/config.clj`
- `src/mcp_injector/core.clj`
- `test/mcp_injector/bifrost_shim_test.clj` (new)

______________________________________________________________________

## 2026-02-12 - Session 005

### New Work: Bifrost Error Handling

**Problem:** Real Bifrost will fail (rate limits, outages, timeouts). Need graceful error handling.

**Implementation:**

1. **Extended test-bifrost-server.clj:**

   - Added support for error responses (`:type :error`)
   - Added timeout simulation (`:type :timeout` with delay)
   - Can now test 429, 500, 503 errors

1. **Updated core.clj error handling:**

   - Added 30-second timeout to all Bifrost calls
   - Proper error response mapping:
     - 429 → 429 (rate limit, preserve for client retry logic)
     - 500/502/503 → 502 (bad gateway, our upstream failed)
     - Timeout → 504 (gateway timeout)
     - Connection failure → 503 (service unavailable)
   - Error responses formatted correctly for both SSE and JSON modes
   - No stack traces leaked to clients

1. **Added 3 critical error tests:**

   - `test-bifrost-rate-limit`: Verifies 429 handling with SSE error format
   - `test-bifrost-server-error`: Verifies 502 handling with JSON error format
   - `test-bifrost-timeout`: Verifies timeout doesn't hang forever

**Error Response Format:**

```json
{
  "error": {
    "message": "Rate limit exceeded",
    "type": "rate_limit_exceeded",
    "details": {...}
  }
}
```

**Test Results:**

- All 15 tests passing (7 integration + 8 bifrost shim)
- 40 total assertions
- Zero lint warnings

______________________________________________________________________

### Deferred Test Ideas (Don't Lose These!)

**For when we add real MCP support:**

- MCP server unreachable (connection refused)
- MCP server returns JSON-RPC error response
- get_tool_schema meta-tool actually fetches and caches schema
- Schema caching (don't fetch same schema twice)
- Mixed tool calls: MCP + non-MCP in same response (we test pass-through but not full flow)

**For content/edge case hardening:**

- Special characters in content (newlines, quotes, unicode) breaking SSE format
- Very large responses (chunking issues)
- Empty content responses
- Empty fallbacks array in config
- Malformed OpenAI requests (missing required fields)
- Invalid JSON in request body

**For production observability:**

- Request/response logging verification
- Error metrics/counts
- Request timing measurements
- Concurrent request safety (state pollution between requests)

**Performance/stress tests:**

- Memory usage under load
- Connection pooling with Bifrost
- Backpressure when Bifrost is slow

______________________________________________________________________

### Next Steps

1. Test with real Bifrost instance
1. Verify timeout handling in production (30s may need adjustment)
1. Monitor for edge cases
1. Production deployment testing

______________________________________________________________________

## 2026-02-12 - Session 003

**Phase 1 Core Runtime - COMPLETE!**

[Previous session content...]
