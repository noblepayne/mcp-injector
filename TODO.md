how to handle 502? which might be context issue. how to make sure we communicate it back to openclaw? are we?

- last time I hand stopped the shim an then it seemd to send a "hey do a new session" and that worked, and I bounced the shm

- but... that's not ideal.

- [ ] Add robust configuration validation for mcp-servers.edn (e.g. ensure :url not :uri, check required fields)

- [ ] Add startup check to verify connectivity to all configured MCP servers

- [ ] Automatically refresh tool directory from MCP servers at startup or periodically (Phase 3)

- [ ] Support both :url and :uri in server config for better UX/compatibility

### Native Tools (2026-02-25)

- [x] Add clojure-eval native tool (2026-02-25)
- [x] Add routing for native tools in agent loop (2026-02-25)
- [x] Add clojure-eval tool definition in config (2026-02-25)
- [x] Run tests: `bb test` (2026-02-25)
- [x] Run linting: `clj-kondo --lint src/ test/` (2026-02-25)
- [x] Run formatting: `cljfmt check src/ test/` (2026-02-25)
- [ ] Add more native tools: bb, read, write, exec (future)
- [ ] Consider SCI for sandboxed eval (future)

### Future Enhancements

- [ ] Per-project nREPL contexts (for NACME, BoostBox, etc.)
- [ ] Project-aware clojure-eval: `(clojure-eval {:project :nacme :code "(nacme/refresh!)"})`
- [ ] Add tests for bb, read, write, exec native tools




- [ ] look into errors string vs number on llm upstream issues? see prism logs


- [ ] errors on parsing like invalid json, just gives null back to curl?


- [ ] logging how initializing of mcp but not clear on which one...
