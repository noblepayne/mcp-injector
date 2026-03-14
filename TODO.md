1: how to handle 502? which might be context issue. how to make sure we communicate it back to openclaw? are we?
2: 
3: - last time I hand stopped the shim an then it seemd to send a "hey do a new session" and that worked, and I bounced the shm
4: 
5: - but... that's not ideal.
6: 
7: - [ ] Add robust configuration validation for mcp-servers.edn (e.g. ensure :url not :uri, check required fields)
8: 
9: - [ ] Add startup check to verify connectivity to all configured MCP servers
10: 
11: - [ ] Automatically refresh tool directory from MCP servers at startup or periodically (Phase 3)
12: 
13: - [ ] Support both :url and :uri in server config for better UX/compatibility
14: 
15: ### Native Tools (2026-02-25)
16: 
17: - [x] Add clojure-eval native tool (2026-02-25)
18: - [x] Add routing for native tools in agent loop (2026-02-25)
19: - [x] Add clojure-eval tool definition in config (2026-02-25)
20: - [x] Run tests: `bb test` (2026-02-25)
21: - [x] Run linting: `clj-kondo --lint src/ test/` (2026-02-25)
22: - [x] Run formatting: `cljfmt check src/ test/` (2026-02-25)
23: - [ ] Add more native tools: bb, read, write, exec (future)
24: - [ ] Consider SCI for sandboxed eval (future)
25: 
26: ### Governance & Security (2026-03-14)
27: 
28: - [x] Advanced PII scanning and redaction (Stage 1)
29: - [x] Signed Audit Trail with ULID and HMAC (Stage 2)
30: - [x] Tool Access Policy with Permissive/Strict modes (Stage 3)
31: - [x] JSON-RPC request interception for Sampling (Stage 3 Infrastructure)
32: - [ ] Implement `sampling/createMessage` routing to LLM chain (After Stage 3)
33: - [ ] Use SCI (Small Clojure Interpreter) for complex policy predicates (e.g. path whitelists)
34: - [ ] Support robust Glob patterns in policy rule matching (instead of prefix wildcards)
35: 
36: ### Future Enhancements
37: 
38: - [ ] Per-project nREPL contexts (for NACME, BoostBox, etc.)
39: - [ ] Project-aware clojure-eval: `(clojure-eval {:project :nacme :code "(nacme/refresh!)"})`
40: - [ ] Add tests for bb, read, write, exec native tools
41: 
42: 
43: 
44: 
45: - [ ] look into errors string vs number on llm upstream issues? see prism logs
46: 
47: 
48: - [ ] errors on parsing like invalid json, just gives null back to curl?
49: 
50: 
51: - [ ] logging how initializing of mcp but not clear on which one...
52: 
53: 
54: - [ ] more dynamic api control, turn on "paid mode" or swap models, or swap chains behind a virtual model?
55:   - paid mode for like "I am doing a lot fo work, I don't want to wait for fallback chains trying free models right now, but will want to return to steady state of that later"
56: 
57: 
58: - Sampling protocol support: MCP's sampling feature inverts the normal tool-call flow — instead of the agent calling MCP servers, an MCP server can mid-execution request an LLM completion *back through the client*. This means MCP servers can orchestrate intelligent behavior without managing their own API keys or LLM infrastructure; the user's existing model config handles it. Implementation in mcp-injector would be relatively contained: the STDIO reader loop in `mcp-client-stdio.clj` (and the HTTP session handler) already process inbound JSON-RPC — sampling just requires intercepting `sampling/createMessage` requests from servers and routing them through the existing `call-llm` + virtual model chain logic rather than ignoring them. The virtual model chain is the right mechanism here: servers express preferences via `costPriority`, `speedPriority`, and `intelligencePriority` floats (0-1), which could map to provider selection within the `brain` chain. Security consideration: since this runs headless, decide ahead of time which MCP servers are trusted to make sampling calls — untrusted servers with sampling access can essentially prompt-inject through the LLM. Reference implementation: `SecretiveShell/MCP-Bridge` `sampling/sampler.py` + `modelSelector.py` (Euclidean distance model selection).
