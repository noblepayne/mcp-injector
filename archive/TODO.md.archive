# TODO.md - mcp-injector

> Quick notes and pending tasks. See `dev/log.md` for detailed session notes.

---

## Open Issues

### Configuration & Validation
- [ ] Add robust configuration validation (e.g. ensure :url not :uri, check required fields)
- [ ] Add startup check to verify connectivity to all configured MCP servers
- [ ] Support both :url and :uri in server config for better UX/compatibility

### MCP Tool Discovery
- [ ] Automatically refresh tool directory from MCP servers at startup or periodically (Phase 3)

### Native Tools (Future)
- [ ] Add more native tools: bb, read, write, exec
- [ ] Consider SCI for sandboxed eval

### Governance & Security
- [ ] Implement `sampling/createMessage` routing to LLM chain
- [ ] Use SCI for complex policy predicates (e.g. path whitelists)
- [ ] Support robust Glob patterns in policy rule matching

### Error Handling
- [ ] Investigate: 502 handling - context issue vs other errors
- [ ] Investigate: LLM upstream error response types (string vs number)
- [ ] Investigate: JSON parsing errors - proper error response to clients

### Observability
- [ ] Improve MCP server initialization logging (which server is initializing)
- [ ] Add more dynamic API control (paid mode, swap models/chains at runtime)

### Future Enhancements
- [ ] Per-project nREPL contexts (for NACME, BoostBox, etc.)
- [ ] Project-aware clojure-eval: `(clojure-eval {:project :nacme :code "(nacme/refresh!)"})`
- [ ] Add tests for bb, read, write, exec native tools

---

## Completed (Historical)

### Native Tools (2026-02-25)
- [x] Add clojure-eval native tool
- [x] Add routing for native tools in agent loop
- [x] Add clojure-eval tool definition in config

### Governance & Security (2026-03-14)
- [x] Advanced PII scanning and redaction
- [x] Signed Audit Trail with ULID and HMAC
- [x] Tool Access Policy with Permissive/Strict modes
- [x] JSON-RPC request interception for Sampling

### Usage Tracking (2026-03-14)
- [x] Provider-level usage tracking (tokens, requests, rate-limits)
- [x] Virtual model fallback telemetry
- [x] Atomic usage recording (model + provider)

---

## Rough Notes (from ideas.md)

- Sometimes nvidia/kimi doesn't respond - retry logic may be needed
- Check for no response, try again even if it looks good otherwise

---

## Sampling Protocol (Research Notes)

MCP's sampling feature inverts the normal tool-call flow — MCP servers can request an LLM completion back through the client. Implementation would be contained:

- STDIO reader loop in `mcp-client-stdio.clj` already processes JSON-RPC
- Intercept `sampling/createMessage` requests and route through virtual model chain
- Virtual model chain handles `costPriority`, `speedPriority`, `intelligencePriority` preferences
- Security: Only trusted servers should have sampling access (prompt injection risk)
- Reference: `SecretiveShell/MCP-Bridge` sampling/sampler.py + modelSelector.py





### INBOX
- models endpoint?
- test our meta endpoints
- make listing and searching tools proper first class tools themselves



Expose list/search as injectable tools — Turn /api/v1/mcp/tools into a native list_mcp_tools (with optional filter param) and search_mcp_tools (keyword/semantic via description match). Agent calls them first → narrows → then get_tool_schema → even less noise in initial prompt.
Auto-schema on first call — Optional policy flag to fetch schema automatically on first invocation (skip explicit get_tool_schema call) — trade a bit of latency for less agent reasoning.
Better formatting — Current param hints ([id, limit?]) are nice; could evolve to short JSON examples if models respond better.



TUNE stuff, we see tools that say "high entropy secret" often. but its... ust the tool name etc.



### Context Awareness Engine (Future)

**Priority**: Medium | **Status**: Not Started

Currently, when a virtual model chain receives a 503 (Context Overflow), it advances to the next provider. However, most providers in a chain share the same context window (e.g., all "gpt-4o-mini" providers have ~128K windows).

**Problem**: Advancing the chain on 503 wastes quota—the next provider has the same limit and will likely fail too.

**Proposed Solution**: Implement a "Context Compactor" model:
1. Detect 503 (Context Overflow) from any provider
2. Before retrying, invoke a lightweight "compactor" model (e.g., `gpt-4o-mini`) to summarize the conversation history
3. Retry the original model with the compressed context
4. This preserves the preferred model while fitting within its window

**Alternative**: For now, we rely on upstream agents (like OpenClaw) to handle compaction via session reset. This is the simpler "reliability over features" approach.
