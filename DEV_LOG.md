# DEV_LOG.md - Tool Auto-Discovery Feature

## Goal
Auto-discover tools from MCP servers at runtime instead of relying solely on config lists.

## Changes

### -19

#### Plan
- **2026-02Lazy + cached** approach: fetch tools on first request per server
- Filter by config:
  - `:tools nil` → discover all
  - `:tools []` → discover none (empty)
  - `:tools ["foo" "bar"]` → only those tools
- No cache invalidation (cache forever until restart)

#### Files to modify
1. `src/mcp_injector/mcp_client.clj` - add tool cache, discover-tools fns
2. `src/mcp_injector/config.clj` - update inject-tools-into-messages, build-tool-directory
3. `src/mcp_injector/core.clj` - wire up discovery

#### Tests to add
- Config nil → shows all discovered tools
- Config [] → shows none
- Config [specific] → shows only those

---

## Progress

- [x] Create DEV_LOG.md (this file)
- [x] Write tests for tool discovery filtering
- [x] Update mcp_client.clj with tool cache
- [ ] Update config.clj to use discovered tools (in progress)
- [ ] Run tests
- [ ] Lint and format

---

## Implementation Complete (2026-02-20)

### Summary
- Added `mcp/discover-tools` function with caching
- Added filtering by config `:tools` list:
  - `nil` → discover all
  - `[]` → discover none
  - `["foo" "bar"]` → only those
- Added error handling for unreachable MCP servers
- Updated config to accept pre-discovered tools
- Added tests for filtering behavior

### Tests Added
- `tool-discovery-filtering-nil-shows-all`
- `tool-discovery-filtering-empty-shows-none` 
- `tool-discovery-filtering-specific-shows-only-those`
- `tool-discovery-caches-results`

---

## Additional Bug Report (2026-02-20)

**Issue:** MCP proxy with stdio server doing streamablehttp - complex output causes parsing errors.

**Error:** 
```
Unexpected character ('_' (code 95)): was expecting comma to separate Object entries
```

**Root cause:** LLM is returning malformed JSON in tool arguments - looks like duplicate keys or bad JSON merging:
```
"arguments":"{\"operation\": \"insert\"_after\", ...
```

The `"_after"` suggests the LLM is outputting malformed JSON where `insert_after` got corrupted.

**Status:** Not yet investigated.
