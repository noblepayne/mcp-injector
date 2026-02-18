# mcp-injector

> HTTP shim for injecting MCP tools into OpenAI-compatible chat completions

mcp-injector sits between an agent (like OpenClaw or any OpenAI-compatible client) and LLM gateways (like litellm, bifrost, etc.). It injects MCP tool directories into prompts and implements an agent loop for tool execution.

## What It Does

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
│    Agent    │────▶│ mcp-injector │────▶│ LLM gateway │────▶│     LLM     │
│ (OpenClaw)  │     │              │     │(litellm,    │     │             │
└─────────────┘     └──────────────┘     │ bifrost)    │     └─────────────┘
                            │              └─────────────┘
                            │     ┌──────────┐     ┌──────────────┐
                            └──▶  │ MCP Tool │────▶│ MCP Server   │
                                  │ Execution│     │(stripe, etc.)│
                                  └──────────┘     └──────────────┘
```

**Key Features:**

- ✅ OpenAI-compatible `/v1/chat/completions` endpoint
- ✅ MCP tool directory injection into system prompts
- ✅ Agent loop with tool execution (LLM → tools → LLM...)
- ✅ SSE streaming responses for agents (like OpenClaw) compatibility
- ✅ Support for `get_tool_schema` meta-tool
- ✅ Real HTTP integration tests (no mocks)
- ✅ Babashka-based for fast startup (~100MB binary)

## Quick Start

### Prerequisites

- [Babashka](https://babashka.org/) installed
- [Nix](https://nixos.org/) (optional, for reproducible environment)

### Installation

```bash
# Clone the repository
git clone <repo-url>
cd mcp-injector

# Enter Nix shell (optional but recommended)
nix develop

# Run tests
bb test

# Start the server
bb run
```

### Configuration

Create `mcp-servers.edn`:

```clojure
{:servers
  {:stripe
   {:url "http://localhost:3001/mcp"
    :tools ["retrieve_customer" "list_charges"]}
   :postgres
   {:url "http://localhost:3002/mcp"
    :tools ["query" "execute"]}}}
```

Set environment variables (optional):

```bash
export MCP_INJECTOR_PORT=8080
export MCP_INJECTOR_BIFROST_URL=http://localhost:8081
export MCP_INJECTOR_MCP_CONFIG=./mcp-servers.edn
```

### Usage

Once running, mcp-injector accepts OpenAI-compatible requests:

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "List my Stripe customers"}],
    "stream": true
  }'
```

## Architecture

### Core Modules

- **`core.clj`** - HTTP server, request handlers, main entry point
- **`config.clj`** - Environment config, MCP server registry, tool injection
- **`openai_compat.clj`** - OpenAI API parsing, SSE streaming
- **`mcp_client.clj`** - MCP HTTP client (tools/list, tools/call)
- **`agent_loop.clj`** - Agent execution loop with iteration limits

### Data Flow

1. **Request**: Agent sends request with `stream=true`
1. **Tool Injection**: mcp-injector adds MCP tool directory to system prompt
1. **LLM Call**: Forward to LLM gateway with `stream=false`
1. **Tool Detection**: Check LLM response for `tool_calls`
1. **Tool Execution**: Call MCP servers, get results
1. **Loop**: Send results back to LLM, repeat until no more tools
1. **Response**: Stream final result to agent as SSE

## Development

### Running Tests

```bash
# Run all tests
bb test

# Tests use real HTTP servers (no mocks!)
# - Test MCP Server (simulates MCP protocol)
# - Test LLM gateway Server (simulates LLM responses)
# - mcp-injector (actual system under test)
```

### Linting and Formatting

```bash
# Check code
clj-kondo --lint src/ test/

# Fix formatting
cljfmt fix src/ test/

# Check formatting
cljfmt check src/ test/
```

### Development Workflow

```bash
# 1. Run tests
bb test

# 2. Run linter
clj-kondo --lint src/ test/

# 3. Fix formatting
cljfmt fix src/ test/

# 4. Verify
cljfmt check src/ test/

# 5. Commit
git add -A && git commit -m "..."
```

## Project Philosophy

We follow **grumpy pragmatic** Clojure development:

- **Simple over easy** (Rich Hickey) - Don't complect concerns
- **Actions, Calculations, Data** (Eric Normand) - Keep them distinct
- **Functional core, imperative shell** - Pure business logic, impure edges
- **Write tests first, mostly integration** - Verify actual behavior
- **Data-driven** - Maps over objects, functions over methods
- **YAGNI** - You Aren't Gonna Need It

See `AGENTS.md` for detailed guidelines.

## Project Structure

```
mcp-injector/
├── src/
│   └── mcp_injector/
│       ├── core.clj           # HTTP server, main entry
│       ├── config.clj         # Configuration, env vars
│       ├── openai_compat.clj  # SSE, OpenAI API compat
│       ├── mcp_client.clj     # MCP HTTP client
│       └── agent_loop.clj     # Agent execution loop
├── test/
│   └── mcp_injector/
│       ├── test_mcp_server.clj      # Test MCP server
│       ├── test_llm_server.clj  # Test LLM simulator
│       └── integration_test.clj     # Full stack tests
├── dev/
│   ├── backlog.edn      # Task list
│   ├── current.edn      # Current session
│   ├── log.md          # Development log
│   └── decisions.edn   # Architecture decisions
├── bb.edn              # Babashka config
├── mcp-servers.edn     # MCP server definitions
└── AGENTS.md           # Development guidelines
```

## Roadmap

### Phase 1: Core Runtime ✅ COMPLETE

- [x] HTTP server with OpenAI-compatible endpoint
- [x] MCP tool directory injection
- [x] Agent loop with tool execution
- [x] SSE streaming
- [x] Integration tests

### Phase 2: Progressive Discovery

- [ ] MCP directory injection (compact format)
- [ ] Schema caching
- [ ] `get_tool_schema` optimization
- [ ] Tool categorization (full vs lazy)

### Phase 3: Production Hardening

- [ ] Error handling & retries
- [ ] Timeouts
- [ ] Rate limiting
- [ ] Observability (OpenTelemetry)
- [ ] Metrics & logging

## License

MIT License - See LICENSE file

## Contributing

See `AGENTS.md` for development guidelines and `dev/` for project tracking.

______________________________________________________________________

**Status**: Phase 1 Complete | **Tests**: 7 passing, 17 assertions | **Built with**: Babashka + http-kit + Cheshire
