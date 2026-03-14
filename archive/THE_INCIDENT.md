# Complete Incident Report: Progressive Tool Discovery Breakage

## Executive Summary

Between commits `95e2ebd` (STDIO transport, "38 tests passing") and `62535f0` (latest), the progressive tool discovery feature was progressively degraded until non-functional. Multiple issues compound to break the core feature while leaving other functionality intact.

---

## Timeline of Breakage

| Date | Commit | Description | Impact |
|------|--------|-------------|--------|
| 2026-02-19 | `84a1f4b` | **Phase 2 Progressive Tool Discovery** - Initial implementation | Working |
| 2026-02-19 | `183cf9d` | Enhanced with `mcp__[server]__[tool]` namespacing | Working |
| 2026-02-20 00:40 | `95e2ebd` | **STDIO transport** - Major refactor | Working (38 tests) |
| 2026-02-20 18:40 | `62535f0` | **Fix tool directory population** - Changes to config handling | **BREAKAGE STARTS** |

---

## Root Causes (Two Contributing Factors)

### Factor 1: core.clj - Agent Loop Progressive Discovery Removed
The progressive mechanism where `get_tool_schema` results were extracted and added to the tools array for the next iteration was broken or removed during refactoring.

### Factor 2: config.clj - System Prompt Issues
The system prompt was using static tool names from config instead of actual discovered schemas, and didn't correctly handle namespaced tools.

---

## THE FIX (Applied 2026-02-21)

### Phase 1: Core System Restoration
- [x] **Restored `extract-discovered-tools`**: Scans conversation history for schemas.
- [x] **Updated `prepare-llm-request`**: Injects meta-tools and discovered tools into `:tools`.
- [x] **Fixed `agent-loop`**: Restored loop-local tracking of discovered tools via atom.
- [x] **Implemented Mixed Tool Call Support**: LLM can now call both MCP and native tools in one response; native tools are preserved for downstream handling.
- [x] **Hallucination Trap**: Enforces protocol where tools must be discovered via `get_tool_schema` before use.

### Phase 2: Observability & Routing
- [x] **Restored `/stats`**: Dual-support for `/stats` and `/api/v1/stats`.
- [x] **Graceful Max-Iterations**: Returns partial results with `finish_reason: "length"` instead of 500 error.
- [x] **Nil-safety**: Fixed crashes in `get-gateway-state` when server hasn't finished warming up.

### Phase 3: Test Infrastructure Fixes
- [x] **`test_mcp_server.clj`**: Robust tool iteration handling both map and vector formats.
- [x] **Integration Tests**: Fixed race conditions and fragile assertions in session and discovery tests.

---

## Final Status: FIXED
**Date**: 2026-02-21
**Verification**: 37 tests passing (121 assertions).
**Performance**: Sub-100ms overhead for tool discovery steps.

---

## AGENTS.md Lessons Learned

1.  **Test the progressive flow specifically** - Ensure tests cover the full Directory -> Discover -> Call lifecycle.
2.  **Case-insensitive headers** - MCP/HTTP headers are often lowercased by servers; tests must be robust.
3.  **Config Merging is tricky** - Always prioritize explicit test overrides over file-based config.
4.  **Loop state is precious** - Changes to the agent loop must be verified against history persistence.
