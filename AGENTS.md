# AGENTS.md - mcp-injector

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## Philosophy: Grumpy Pragmatism

We write **situated programs** that are reliable, robust, and data-driven. We reject OOP nonsense in favor of simple, composable systems. This codebase follows the philosophy of grumpy senior developers who've been burned by complexity:

### Test-First Design

We practice **test-driven development with real integration tests**:

- **Write tests first** - Define expected behavior before implementation
- **Real servers, not mocks** - Spin up in-process HTTP servers for testing
- **Integration over unit** - Test the full request/response cycle
- **Don't mock what you don't own** - Test against real protocol implementations

**Why real servers?**

- Tests verify actual HTTP behavior, not mocked assumptions
- Refactoring confidence - tests catch real breakage
- Living documentation - tests show how the system works end-to-end
- No mock drift - tests catch API/protocol changes immediately
- Debugging aid - test servers log exactly what was sent/received

**Test Infrastructure Pattern:**

```
Test MCP Server (real http-kit server)
    ↓
mcp-injector (system under test)
    ↓
Test LLM gateway Server (real http-kit server)
```

All three run in-process, tests make real HTTP requests through the full stack.

### Simple Over Easy (Hickey)

- **Complection is the enemy** - Don't twist things together
- Separate concerns: data, behavior, state, identity, time
- Choose simple constructs even when they're unfamiliar
- Easy now often means painful later

### Actions, Calculations, Data (Normand)

Keep these distinct:

- **Actions** - Functions with side effects (HTTP calls, I/O)
- **Calculations** - Pure functions, deterministic, testable
- **Data** - Maps, vectors, primitives - just data

### Functional Core, Imperative Shell

- Core business logic: pure functions (calculations)
- Shell: handles side effects, orchestration, I/O
- Push impurity to the edges
- Test the core thoroughly, shell sparingly

### YAGNI (You Aren't Gonna Need It)

- Don't build for imagined futures
- Solve today's problem, make change easy
- Abstractions are a cost, not a benefit
- "What if we need..." is usually wrong

### Testing Philosophy

> "Write tests. Not too many. Mostly integration."

- **Integration tests** verify the system actually works
- Unit tests are guardrails for pure functions
- Don't mock what you don't own
- Test behavior, not implementation
- Avoid testing trivial code

### Data-Driven, Not Class-Driven

- Maps over objects
- Functions over methods
- Transformations over mutations
- Composition over inheritance
- "Just use a map" is often the right answer

### Systems Thinking (Wayne)

- Think in states and transitions
- Explicit state machines beat distributed state soup
- Verify designs before coding
- Failures are part of the design

### Production-Ready, Not Overbuilt

- Robustness > cleverness
- Reliability over features
- Observable, debuggable, operable
- Ship small, ship often
- Handle failure gracefully

## Your Defaults

**Language:** Babashka (`bb`) unless there's a specific reason for Clojure proper. Babashka starts fast, ships as a single binary, runs anywhere, and has enough of the ecosystem to get real work done. Don't reach for full Clojure unless you need something Babashka can't do — AOT compilation, specific Java interop, performance-critical inner loops.

**Data:** Plain maps and vectors. EDN for config and internal formats. JSON only at the boundary (HTTP in/out). Don't invent wrapper types when a map with a well-chosen key does the job.

**State:** Atoms for shared mutable state. Refs if you need coordinated transactions (you usually don't). Avoid global mutable state except where it's the obvious right answer (SSE subscriber registry, connection pools).

**I/O:** `babashka.http-client` for outbound HTTP. `org.httpkit.server` for serving. `pod-babashka-go-sqlite3` for SQLite. Flat files with content-addressed names for blobs. No ORMs. No connection pools unless you're hitting the same bottleneck twice.

**Concurrency:** `future` for fire-and-forget. `core.async` channels for fan-out. Remember: in Babashka, `go` blocks use threads (no real parking) — fine for tens of connections, not for thousands.

**Error handling:** `ex-info` with a data map. Catch at the boundary. Don't swallow exceptions silently. Log the message and the relevant context.

**Dependencies:** As few as possible. Check if Babashka's built-in namespaces do the job before adding a dep. When you do add one, pin the version.

## Build/Test/Lint Commands

### Development Shell

```bash
# Enter Nix development shell (required for all commands)
nix develop
```

### Running

```bash
# Start the runtime server
bb run

# Start REPL
bb repl

# Build Nix package
nix build

# Run directly via Nix
nix run
```

### Development Workflow

**Always run linting and formatting before committing:**

```bash
# 1. Run tests
bb test

# 2. Run linter
clj-kondo --lint src/ test/

# 3. Fix formatting
cljfmt fix src/ test/

# 4. Verify formatting
cljfmt check src/ test/

# 5. Commit
bb test && clj-kondo --lint src/ test/ && cljfmt check src/ test/
```

### Testing

```bash
# Run all tests
bb test

# Run a single test (example pattern)
bb test --focus mcp-injector.integration-test/test-name

# Note: Tests are in test/ directory with *_test.clj naming
# Integration tests use real in-process servers, not mocks
```

### Linting & Formatting

```bash
# Lint Clojure code
clj-kondo --lint src/ test/

# Format Clojure code
cljfmt fix src/ test/

# Check formatting without changes
cljfmt check src/ test/

# Format Markdown
mdformat NIX_USAGE.md situated-agent-runtime-spec.md
```

## Code Style Guidelines

### Clojure Conventions

**Naming:**

- Functions: `kebab-case` (e.g., `handle-chat-completion`, `call-mcp-tool`)
- Constants: `UPPER_SNAKE_CASE` for env vars, `*earmuffs*` for dynamic vars
- Namespaces: `mcp-injector.module-name` (e.g., `mcp-injector.core`, `mcp-injector.mcp-client`)
- Protocols/Records: `PascalCase`
- Private functions: suffix with `-` (e.g., `helper-fn-`)

**Imports:**

```clojure
(ns mcp-injector.module
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.curl :as curl]
            [cheshire.core :as json]))
```

- Group: standard lib → third-party → internal
- Always use `:as` aliases
- Prefer `:require` over `:use`

**Formatting:**

- 2-space indentation
- 80-100 character line limit
- Align map values and let bindings
- Trailing newline at EOF

**Error Handling:**

```clojure
;; Use try/catch with specific exceptions
(try
  (risky-operation)
  (catch Exception e
    (log/error e "Operation failed")
    {:error "friendly message"}))

;; Return consistent error shapes
{:error "description" :details additional-info}
```

### Babashka-Specific

- Prefer built-in `babashka.*` libraries over external deps
- Use `curl` for HTTP (already available)
- Keep startup time fast - avoid heavy JVM deps
- Use `bb.edn` for minimal deps (cheshire, org.httpkit.server)

### Babashka Coding Standards

**Single file is fine.** If a program fits in one file (\<1000 lines), don't split it into namespaces. Babashka doesn't need a build step — the file IS the program.

**Comments explain WHY not WHAT.** The code shows what. Comments explain tradeoffs, non-obvious choices, and the reasoning behind a decision.

**ULID for IDs.** Not UUID. ULIDs are sortable, which means `WHERE id > ?` gives you replay for free. Don't swap this out casually.

**JSON at the boundary, keywords inside.** Decode with `true` for keyword keys. Never pass JSON strings around internally.

**First-write-wins for claims.** This is intentional. No distributed locking. SQLite's serialized writes make `UPDATE ... WHERE status='open'` safe for small deployments.

**SSE fan-out via atom of sets.** The atom maps channel-name → set of open http-kit channels. Dead clients are cleaned up on write error.

**Immutability for blobs.** Content-addressed by sha256. Never delete them. Storage cleanup is out of scope.

### Project Structure

```
src/
  mcp_injector/           ; Note: underscore for Clojure filenames
    core.clj              # HTTP server, main entry
    openai_compat.clj     # SSE formatting, OpenAI API compat
    agent_loop.clj        # Agent execution loop
    mcp_client.clj        # HTTP MCP client
    config.clj            # Configuration, env vars
test/
  mcp_injector/
    test_mcp_server.clj   # Real MCP server for testing
    test_llm_server.clj # Real LLM gateway for testing
    integration_test.clj  # Full stack integration tests
bb.edn                    # Babashka config
mcp-servers.edn           # MCP server configurations
```

### Environment Variables

```bash
MCP_INJECTOR_PORT=8080                    # Server port
MCP_INJECTOR_HOST=127.0.0.1              # Bind address
MCP_INJECTOR_LLM_URL=http://...      # LLM endpoint
MCP_INJECTOR_LOG_LEVEL=info              # debug|info|warn|error
MCP_INJECTOR_MAX_ITERATIONS=10           # Agent loop limit
MCP_INJECTOR_MCP_CONFIG=/path/to/mcp-servers.edn
```

### MCP Server Configuration (mcp-servers.edn)

```clojure
{:servers
  {:stripe
   {:url "http://localhost:3001/mcp"
    :tools ["retrieve_customer" "list_charges"]
    :inject :lazy}  ; :full, :lazy, or :none (Phase 1: ignore :inject field)
   :postgres
   {:url "http://localhost:3002/mcp"
    :tools ["query" "execute"]
    :inject :lazy}}}
```

**Note:** Phase 1 implements basic tool injection. The `:inject` field (controlling full vs lazy injection) will be implemented in Phase 2.

### Type Hints (Optional but appreciated)

```clojure
(defn handle-request [^java.util.Map request]
  "Handle incoming HTTP request")
```

## Nix Commands

```bash
# Install to profile
nix profile install

# Update flake inputs
nix flake update

# Check flake
nix flake check

# Format Nix files
nixfmt flake.nix
```

## Testing Guidelines

- Tests in `test/` directory mirror `src/` structure
- Use `clojure.test` (built into Babashka)
- Mock HTTP calls with `with-redefs`
- Test files: `*_test.clj` suffix
- Run single test namespace: `bb test -n mcp-injector.integration-test`

### Test Infrastructure Design

**Real Servers Pattern:**

Instead of mocking HTTP calls, we spin up real in-process servers:

```clojure
;; Test MCP Server - implements actual MCP protocol over HTTP
(start-test-mcp-server)
;; Returns: {:port 12345 :stop fn :received-requests atom}

;; Test LLM gateway Server - simulates LLM responses
(start-test-llm-server)
;; Returns: {:port 12346 :stop fn :set-response fn}
```

**Test Fixture Pattern:**

```clojure
(use-fixtures :once
  (fn [test-run]
    (let [mcp-server (start-test-mcp-server)
          llm-server (start-test-llm-server)
          injector-server (start-mcp-injector
                           {:mcp-port (:port mcp-server)
                            :llm-port (:port llm-server)})]
      (try
        (test-run)
        (finally
          (stop-server mcp-server)
          (stop-server llm-server)
          (stop-server injector-server))))))
```

**Key Testing Principles:**

1. **Port 0 allocation** - Let OS assign random free ports to avoid conflicts
1. **Request tracking** - Test servers store received requests for assertions
1. **Configurable responses** - LLM gateway simulator can return predetermined responses
1. **Fast startup** - http-kit servers start in \<100ms, no Docker overhead
1. **In-process** - Everything runs in same JVM for easy debugging

**Example Test:**

```clojure
(deftest single-tool-call-test
  (testing "Agent loop executes tool and returns result"
    ;; Configure test LLM gateway to return tool_calls on first request
    (set-next-response test-llm
                       {:role "assistant"
                        :tool_calls [{:name "stripe.retrieve_customer"
                                      :arguments {:customer_id "cus_123"}}]})
    
    ;; Configure test MCP to return tool result
    (set-mcp-response test-mcp "stripe.retrieve_customer"
                      {:id "cus_123" :email "customer@example.com"})
    
    ;; Configure final LLM response (no tools)
    (set-next-response test-llm
                       {:role "assistant"
                        :content "Found customer: customer@example.com"})
    
    ;; Make request to mcp-injector
    (let [response (http/post "http://localhost:8080/v1/chat/completions"
                              {:body (json/generate-string
                                       {:model "gpt-4o-mini"
                                        :messages [{:role "user"
                                                    :content "Find customer cus_123"}]
                                        :stream true})})]
      
      ;; Assert SSE response format
      (is (= 200 (:status response)))
      (is (string/includes? (:body response) "data:"))
      
      ;; Assert MCP server received the tool call
      (let [requests @(:received-requests test-mcp)]
        (is (= 1 (count requests)))
        (is (= "tools/call" (get-in (first requests) [:params :method])))))))
```

### Integration Testing Philosophy

**Focus on behavior, not implementation:**

```clojure
;; Good: Tests what the system does
(deftest end-to-end-workflow
  (let [response (handler (test-request {:path "/api/data"}))
        body (parse-body response)]
    (is (= 200 (:status response)))
    (is (= {:result "success"} body))))

;; Avoid: Tests implementation details
(deftest implementation-detail
  (testing "private helper function"
    (is (= 5 (private-helpers/some-helper 3))))) ;; Don't test this directly
```

**Prefer testing at boundaries:**

- Test HTTP endpoints with real requests
- Test database interactions with actual queries
- Use testcontainers for external services
- Pure functions in core - test those with simple inputs/outputs

**Test only what's valuable:**

- Skip tests for trivial getters/setters
- Skip tests for configuration
- Test complex business logic
- Test failure modes and edge cases
- Test the "happy path" once, focus on error cases

**Don't mock what you don't own:**

- Use real HTTP clients with test servers
- Use in-memory databases
- Use real file systems (temp directories)
- Mocks create fragile tests that break on refactoring

**Example of good vs bad testing:**

```clojure
;; BAD: Over-mocked, tests implementation
(deftest bad-test
  (with-redefs [http/get (constantly {:status 200})]
    (testing "calls external API"
      (is (service/call-api)))))

;; GOOD: Tests behavior with real integration
(deftest good-test
  (let [server (start-test-server)
        response (service/call-api)]
    (is (= 200 (:status response)))
    (is (= "expected-data" (:body response)))
    (stop-test-server server)))
```

**Test file naming:**

- `*_test.clj` for unit/integration tests
- `*_integration_test.clj` for slow integration tests
- Group tests by behavior, not by function

**Running tests:**

```bash
# Run all tests
bb test

# Run only integration tests
bb test --focus integration

# Run specific test namespace
bb test -n mcp-injector.integration-test

# Run specific test
bb test -n mcp-injector.integration-test/test-name
```

**Test data:**

- Use factories or builders, not fixtures
- Prefer literal data over complex setup
- Make test data obvious and minimal
- One test, one concept

**Example:**

```clojure
;; Good: Clear what we're testing
(deftest process-payment-test
  (let [payment {:amount 100 :currency "USD" :user-id "u123"}]
    (is (= {:status :success} (process-payment payment)))))

;; Bad: Hidden in setup, too much context
(deftest process-payment-test
  (let [payment (create-test-payment-with-all-fields)] ;; What does this do?
    (is (= {:status :success} (process-payment payment)))))
```

**When to write tests:**

- When fixing a bug (write test first)
- When adding complex business logic
- When the cost of failure is high
- When you're unsure if code works

**When NOT to write tests:**

- Configuration code
- Simple data transformations
- Code that's already covered by integration tests
- Throwaway scripts
- "Just trust it" code (simple CRUD)

Remember: Tests are a cost. Write them when they provide value.

## Lessons Learned from mcp-injector Development

### Babashka-Specific Tips

**Port Handling with http-kit:**

```clojure
;; Always extract actual port from server metadata
(let [srv (http/run-server handler {:port 0})
      port (:local-port (meta srv))]
  (println "Server on port:" port))
```

**HTTP Client Patterns:**

```clojure
;; Use @ to deref deferred responses
(let [response @(http/post url {:body body})]
  (if (= 200 (:status response))
    (json/parse-string (:body response) true)
    {:error "Request failed"}))
```

**JSON Handling:**

```clojure
;; Parse with keyword keys
(json/parse-string body true)

;; Generate with pretty printing (for debugging)
(json/generate-string data {:pretty true})
```

### When Code Goes Wrong

**Parenthesis Issues:**

- Don't spin for hours fixing unbalanced parens
- After 2-3 attempts, just rewrite the function/file
- Use heredocs for clean file creation:

```bash
cat > file.clj << 'EOF'
(ns ...)
EOF
```

**Namespace Loading Errors:**

- Check `bb -cp "src:test" -m namespace` to isolate issues
- Verify requires match actual usage
- Remove unused requires (clj-kondo will warn)

### Development Workflow That Works

**Small, Tested Commits:**

```bash
# Make a change
# Run tests
bb test

# Lint and format
clj-kondo --lint src/ test/
cljfmt fix src/ test/

# Commit if clean
git add -A && git commit -m "feat: ..."
```

**When to Use Tools:**

- **Edit tool**: Good for simple string replacements
- **clojure-edit tool**: Better for complex function replacements, respects Clojure syntax
- **Bash cat heredoc**: Best when parens are completely tangled

### Anti-Patterns We Avoided

✅ **Did:** Simple HTTP handler functions\
❌ **Avoided:** Complex middleware chains

✅ **Did:** Real HTTP servers in tests\
❌ **Avoided:** Mocking HTTP calls

✅ **Did:** Data-driven configuration (EDN)\
❌ **Avoided:** Complex config objects

✅ **Did:** Small, focused namespaces\
❌ **Avoided:** God namespaces

✅ **Did:** Pure functions with clear inputs/outputs\
❌ **Avoided:** Hidden state and side effects

### Debugging Strategies

**1. Print Debugging (Still Valid):**

```clojure
(println "Debug:" variable)
;; Check test output to see values
```

**2. Isolate the Problem:**

- Comment out half the code
- Does it work? Problem is in commented half
- Binary search until you find the issue

**3. REPL-Driven Development:**

```bash
# Start REPL
bb repl

# Load namespace
(require '[mcp-injector.core :as core] :reload)

# Test function
(core/start-server {:port 0 :host "127.0.0.1" ...})
```

### Project Tracking That Actually Works

**EDN + Markdown approach:**

- `dev/backlog.edn` - Master task list
- `dev/current.edn` - Current session state
- `dev/log.md` - Running narrative
- `dev/decisions.edn` - Why we chose X over Y

**Why this works:**

- Git-native (versioned with code)
- Session-survivable (files persist)
- No external tools needed
- Readable in any editor
- Works offline

### Commit Message Guidelines

**Format:**

```
<type>: <subject>

<body>
```

**Types:**

- `feat:` - New feature
- `fix:` - Bug fix
- `style:` - Formatting, linting
- `docs:` - Documentation
- `refactor:` - Code restructuring
- `test:` - Adding tests

**Examples:**

```
feat: add tool execution loop with iteration limits

- Implement execute-loop function
- Add tool call parsing
- Handle get_tool_schema meta-tool
- 7 integration tests passing

fix: correct port extraction from http-kit server

Use :local-port from meta instead of assuming port 0
```

## Common Gotchas

### SQLite WAL Mode

Always run `PRAGMA journal_mode=WAL` before any writes. Without it, SQLite defaults to rollback journal which is slower and blocks more. Put it in your `init-db!` function.

### http-kit Thread Pool

Default is 4 workers. For more concurrent SSE clients, bump `{:thread 32}` or higher in your server options.

### Babashka core.async

`go` blocks use real threads (no async parking). Fine for \<100 concurrent connections. If you need more, switch to non-blocking approaches or move to full Clojure + Manifold.

### ULID Monotonicity

The standard ULID impl uses timestamp + random. It's not strictly monotonic under clock skew. Acceptable for most use cases, but don't rely on it for strict ordering if clocks can go backwards.

### History Replay with ULID

`WHERE id > ?` works because ULIDs are lexicographically sortable by time. Don't change the ID scheme without fixing this query pattern — it's the foundation of pagination and replay.

### Port 0 Allocation

Always extract the actual port from server metadata after starting:

```clojure
(let [srv (http/run-server handler {:port 0})
      port (:local-port (meta srv))]
  (println "Server on port:" port))
```

### JSON Parsing

Parse with keyword keys for internal use:

```clojure
(json/parse-string body true)  ; => {:key "value"}
```

Keep JSON strings only at HTTP boundaries.

## Virtual Model Retry Strategy

When configuring `:retry-on` for virtual model chains, think about what each error means:

**Retry (advance to next provider):**

- **429** (rate limit) - Provider busy, try same model elsewhere
- **500** (server error) - Provider broken, try different provider

**Don't retry (let upstream handle it):**

- **503** (context overflow) - Same model = same context limit. Advancing wastes quota. Let OpenClaw compress the session instead.

**Example config:**

```clojure
:virtual-models
{:brain
 {:chain ["zen/kimi-k2.5-free" "nvidia/moonshotai/kimi-k2.5" ...]
  :cooldown-minutes 5
  :retry-on [429 500]}}  ; Don't retry on 503
```

**Why this matters:**
If zen/kimi-k2.5-free hits context limit (503), nvidia/moonshotai/kimi-k2.5 has the **same context window**. It will fail too. Better to return 503 to OpenClaw, which triggers compaction and retry with the same (now working) provider.

**The exception:** If you have a tiered chain with progressively larger context windows:

```clojure
:chain ["small-model-8k" "medium-model-32k" "large-model-128k"]
:retry-on [429 500 503]  ; OK here: advancing actually helps
```

But most chains are same-model-different-provider for redundancy, not tiered.
