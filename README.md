# mcp-injector

> Resilient LLM gateway shim with virtual models, provider fallbacks, and MCP tool injection

mcp-injector sits between an agent (like OpenClaw or any OpenAI-compatible client) and LLM gateways (like litellm, bifrost, etc.). It provides automatic failover across provider chains, translates cryptic errors into actionable messages, and injects MCP tool directories into prompts for agent execution. It supports both remote HTTP MCP servers and local STDIO-based MCP servers.

## What It Does

mcp-injector makes your LLM integration resilient. When a provider rate-limits you, it falls back to another. When a provider returns a cryptic error, it translates it into something your agent can handle. And when your agent needs tools, it handles the execution loop.

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
│    Agent    │────▶│ mcp-injector │────▶│ LLM gateway │────▶│     LLM     │
│ (OpenClaw)  │     │              │     │(litellm,    │     │             │
└─────────────┘     └──────────────┘     │ bifrost)    │     └─────────────┘
                            │            └─────────────┘
                            │     ┌──────────┐     ┌──────────────┐
                            ├──▶  │ MCP Tool │────▶│ HTTP Server  │
                            │     │ Execution│     │(stripe, etc.)│
                            │     └──────────┘     └──────────────┘
                            │            │         ┌──────────────┐
                            │            └────────▶│ Local Process│
                            │                      │(stdio mcp)   │
                            └─────────────────────▶└──────────────┘
```

**Key Features:**

- ✅ **Virtual model chains** - Define fallback providers with cooldowns (e.g., try zen first, fall back to nvidia)
- ✅ **Error translation** - Converts "Cannot read properties of undefined" into "Context overflow: prompt too large"
- ✅ **Smart retry logic** - Retries on 429/500, propagates 503 to trigger compression
- ✅ **Multi-transport MCP** - Support for both HTTP and STDIO (local process) MCP servers
- ✅ **Control & Observability API** - Unified `/api/v1` for state inspection, tool discovery, and cache resets
- ✅ MCP tool directory injection into system prompts
- ✅ Agent loop with tool execution (LLM → tools → LLM...)
- ✅ SSE streaming responses for OpenAI-compatible clients
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
    :tools ["retrieve_customer" "list_charges"]
    ;; Custom HTTP headers (merged with protocol headers)
    :headers {"Authorization" "Bearer sk-xxx"
              "X-Custom-Header" "value"}}

   :postgres
   {:url "http://localhost:3002/mcp"
    :tools ["query" "execute"]}

   :local-tool
   {:cmd ["node" "/path/to/server.js"]
    ;; Static env vars
    :env {"API_KEY" "sk_test_..."}
    ;; Dynamic env vars from environment (supports :prefix and :suffix)
    :env {"DEBUG" {:env "DEBUG_MODE" :prefix "true-" :suffix "-enabled"}
          "PATH" {:env "PATH"}}
    ;; Working directory (static or dynamic via {:env "VAR"})
    :cwd "/path/to/project"}}}

 ;; LLM gateway with virtual models and fallbacks
 :llm-gateway
 {:url "http://localhost:8080"
  :fallbacks ["zen/kimi-k2.5-free" "nvidia/moonshotai/kimi-k2.5"]
  :virtual-models
  {:brain
   {:chain ["provider1/model1" "provider2/model2"]
    :cooldown-minutes 5
    :retry-on [429 500 503]}}}}
```

Set environment variables (optional):

```bash
export MCP_INJECTOR_PORT=8088
export MCP_INJECTOR_LLM_URL=http://localhost:8080
export MCP_INJECTOR_MCP_CONFIG=./mcp-servers.edn
export MCP_INJECTOR_MAX_ITERATIONS=10
```

#### Dynamic Configuration

Environment variables and working directory can reference the system environment:

```clojure
{:env {"DEBUG" {:env "DEBUG_MODE" :prefix "true-" :suffix "-enabled"}
       "TOKEN" {:env "API_TOKEN" :prefix "Bearer "}
       "PATH" {:env "PATH"}
       "HOME" {:env "HOME"}}
 :cwd {:env "PWD"}}
```

- `{:env "VAR"}` - Read from system environment
- `{:env "VAR" :prefix "prefix-"}` - Add prefix to value
- `{:env "VAR" :suffix "-suffix"}` - Add suffix to value
- Combines: `{:env "TOKEN" :prefix "Bearer "}` → `Bearer sk-xxx`

#### HTTP Headers

Custom headers can be added to MCP server requests (useful for auth):

```clojure
{:url "http://localhost:3000/mcp"
 :headers {"Authorization" "Bearer sk-xxx"
           "X-Custom-Header" "value"}}
```

User headers are merged with mandatory protocol headers, with user headers taking precedence.

#### NixOS Deployment

Deploy with the NixOS module:

```nix
services.mcp-injector = {
  enable = true;
  port = 8088;
  llmUrl = "http://localhost:8080";
  
  # Optional: secrets from environment file
  environmentFile = "/etc/secrets/mcp-injector.env";
  
  mcpServers = {
    home-assistant = {
      url = "http://192.168.1.1:8123/api/mcp";
      # Use env var from environmentFile
      env = {
        TOKEN = { env = "HA_TOKEN"; prefix = "Bearer "; };
      };
    };
    stripe = {
      url = "http://localhost:3001/mcp";
      headers = {
        Authorization = "Bearer sk-xxx";
      };
    };
  };
};
```

Create `/etc/secrets/mcp-injector.env`:

```
HA_TOKEN=your-long-lived-access-token
```

The `environmentFile` option loads secrets from a file without exposing them in `/proc` or the Nix store.

### Usage

Once running, mcp-injector accepts OpenAI-compatible requests:

```bash
curl -X POST http://localhost:8088/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "List my Stripe customers"}],
    "stream": true
  }'
```

## Control API

mcp-injector provides a unified JSON API for observability and state management:

- `GET /api/v1/status`: Quick health check and version.
- `GET /api/v1/mcp/tools`: List all discovered tools and session states.
- `POST /api/v1/mcp/reset`: Force clear caches and restart local processes.
- `GET /api/v1/llm/state`: Inspect provider cooldowns and total usage.
- `POST /api/v1/llm/cooldowns/reset`: Clear all provider cooldowns.
- `GET /stats` / `/api/v1/stats`: Usage telemetry.

## Architecture

### Core Modules

- **`core.clj`** - HTTP server, agent loop, main entry point
- **`config.clj`** - Environment config, MCP server registry, tool injection
- **`openai_compat.clj`** - OpenAI API parsing, SSE streaming
- **`mcp_client.clj`** - MCP transport delegation (HTTP/STDIO)
- **`mcp_client_stdio.clj`** - Subprocess-based transport implementation

### Data Flow

1. **Request**: Agent sends request with `stream=true`
1. **Virtual Model Resolution**: Map virtual model to provider chain with fallbacks
1. **Tool Injection**: Add MCP tool directory to system prompt
1. **LLM Call**: Forward to LLM gateway with `stream=false`
1. **Error Handling**: Translate cryptic errors, retry on 429/500, cooldown on failure
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

## Features

**Implemented:**

- ✅ **Multi-transport MCP** - Support for both HTTP and STDIO (local process) MCP servers
- ✅ **Per-server HTTP headers** - Custom headers for MCP server authentication
- ✅ **Dynamic env resolution** - Environment variables with prefix/suffix support
- ✅ **Control & Observability API** - Unified `/api/v1` for state inspection and resets
- ✅ HTTP server with OpenAI-compatible endpoint
- ✅ Virtual model chains with automatic failover
- ✅ Provider cooldown after failures
- ✅ Error translation (Bifrost JS errors → user-friendly messages)
- ✅ Smart retry logic (429/500 retry, 503 propagate)
- ✅ MCP tool directory injection
- ✅ Agent loop with tool execution
- ✅ SSE streaming
- ✅ Real HTTP integration tests (37 tests, 121 assertions)

**Future:**

- [ ] Tool categorization (full vs lazy injection)
- [ ] Observability (OpenTelemetry)
- [ ] Request/response metrics (Prometheus)

## License

MIT License - See LICENSE file

## Contributing

See `AGENTS.md` for development guidelines and `dev/` for project tracking.

______________________________________________________________________

**Status**: Production-ready | **Tests**: 37 passing, 121 assertions | **Built with**: Babashka + http-kit + Cheshire
