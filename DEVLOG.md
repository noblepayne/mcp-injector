# mcp-injector Development Log

High-level progress tracking for the mcp-injector project.

## Project Overview

**mcp-injector** is an HTTP shim that sits between OpenClaw (conversational AI agent) and LLM gateways (like LLM gateway). It:
- Intercepts OpenAI-compatible requests from OpenClaw
- Strips streaming flag and forwards to LLM
- Injects MCP tool directory into prompts
- Implements agent loop for tool execution
- Returns results as SSE stream to OpenClaw

## Current Status

**Phase**: 1 - Core Runtime ✅ COMPLETE  
**Status**: All tests passing, production-ready core  
**Last Updated**: 2026-02-12

### Completed
- [x] Project planning and architecture design
- [x] AGENTS.md with philosophy and guidelines
- [x] Test-first design plan
- [x] Project tracking infrastructure (dev/ directory)
- [x] Test infrastructure with real HTTP servers
- [x] Core HTTP server implementation
- [x] MCP client and tool discovery
- [x] Agent execution loop
- [x] Integration tests (7 tests, 17 assertions, all passing)
- [x] Code cleanup and linting

### Next Phase
- [ ] Progressive tool discovery (Phase 2)
- [ ] Schema caching
- [ ] Production hardening (retries, timeouts, observability)
- [ ] Real MCP server testing

## Architecture

```
OpenClaw (stream=true)
    ↓
mcp-injector (this project)
    - Strips stream flag
    - Injects MCP tool directory
    - Runs agent loop (LLM → tools → LLM...)
    - Returns SSE stream
    ↓
LLM gateway (or any OpenAI-compatible gateway)
    ↓
LLM Provider (OpenAI, Anthropic, etc.)
    ↓
HTTP MCP Servers (stripe, postgres, etc.)
```

## Key Decisions

See `dev/decisions.edn` for full Architecture Decision Records.

- **ADR-001**: Use real HTTP servers for integration testing (not mocks)
- **ADR-002**: Test-first design with integration focus
- **ADR-003**: EDN-based project tracking (git-native, session-survivable)
- **ADR-004**: Babashka over Clojure JVM (fast startup, single binary)
- **ADR-005**: Nix for reproducible development environment

## Development Approach

We follow **grumpy pragmatic** Clojure development:
- Simple over easy (Rich Hickey)
- Actions, Calculations, Data (Eric Normand)
- Functional core, imperative shell
- Write tests first, mostly integration
- Small, tested commits
- Data-driven, not class-driven

## Learnings: Clojure/Babashka Development Best Practices

### What Worked Well

**1. Test-First with Real Servers**
- Integration tests with in-process HTTP servers catch real issues
- No mock drift - tests verify actual behavior
- Tests serve as living documentation
- Pattern: Test MCP Server → mcp-injector → Test LLM gateway Server

**2. Small, Focused Functions**
- Pure functions for business logic (calculations)
- Impure functions only at edges (HTTP, I/O)
- Easy to test, easy to reason about
- Example: `build-tool-directory`, `inject-tools-into-messages`

**3. Data-Driven Configuration**
- EDN files for configuration (mcp-servers.edn)
- Environment variables for deployment-specific values
- Maps over objects, data over methods

**4. Project Tracking in EDN**
- Git-native, session-survivable
- Readable, extensible, no external tools
- backlog.edn + current.edn + log.md pattern

### Technical Lessons

**1. Babashka Port Handling**
```clojure
;; Use :local-port from server meta to get actual port
(let [srv (http/run-server handler {:port 0})
      port (:local-port (meta srv))]
  ...)
```

**2. HTTP Client with http-kit**
- Use `@` to deref deferred responses
- Always check `:status` in response
- Handle errors gracefully with try/catch

**3. JSON Handling with Cheshire**
- `(json/parse-string body true)` for keyword keys
- `(json/generate-string data)` for output
- Consistent error shapes: `{:error "message"}`

**4. Namespace Organization**
- core.clj - Main entry point, HTTP handlers
- config.clj - Configuration loading
- openai_compat.clj - API compatibility
- mcp_client.clj - MCP protocol client
- agent_loop.clj - Business logic

**5. Linting and Formatting**
- Run `clj-kondo --lint src/ test/` regularly
- Use `cljfmt fix src/ test/` before commits
- Prefix unused params with `_` (e.g., `_messages`)
- Remove unused requires

### Development Workflow

**Before Committing:**
```bash
bb test                              # Run tests
clj-kondo --lint src/ test/          # Check for issues
cljfmt fix src/ test/                # Fix formatting
cljfmt check src/ test/              # Verify formatting
git add -A && git commit -m "..."    # Commit
```

**When Stuck:**
1. Check syntax with `bb -cp "src:test" -m namespace`
2. Use `clojure-dev_clojure_edit` for complex edits
3. Rewrite file if parens are too tangled
4. Commit working state before major changes

### What to Avoid

**1. Over-Engineering**
- Started simple, added complexity only when needed
- No premature abstractions
- YAGNI - You Aren't Gonna Need It

**2. Mocking External Services**
- Real test servers > mocks
- Tests catch protocol changes
- More confidence in refactors

**3. Big Bang Changes**
- Small, tested commits
- Easy to bisect if issues arise
- Always have a working state to revert to

## Code Statistics

- **Source**: ~400 lines of Clojure
- **Tests**: ~200 lines
- **Test Coverage**: 7 integration tests, 17 assertions
- **Files**: 5 source + 3 test files
- **Dependencies**: http-kit, cheshire (minimal)

## Resources

- **Spec**: `situated-agent-runtime-spec.md`
- **Guidelines**: `AGENTS.md`
- **Backlog**: `dev/backlog.edn`
- **Current**: `dev/current.edn`
- **Log**: `dev/log.md`
- **Decisions**: `dev/decisions.edn`

---

*Last updated: 2026-02-12 - Phase 1 Core Runtime Complete*
