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
