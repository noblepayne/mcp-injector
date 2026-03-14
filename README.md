# mcp-injector

> Resilient LLM gateway shim with virtual models, provider fallbacks, and secure MCP tool injection

mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides automatic failover, error translation, and a secure governance framework for MCP tool execution.

## Key Features

- ✅ **Virtual model chains** - Define fallback providers with cooldowns.
- ✅ **Governance Framework** - Declarative tool access policies (Permissive/Strict).
- ✅ **PII Scanning** - Automatic redaction of sensitive data in prompts and tool outputs.
- ✅ **Signed Audit Trail** - Tamper-proof NDJSON logs with ULID and HMAC chaining.
- ✅ **Multi-transport MCP** - Support for HTTP and STDIO (local process) MCP servers.
- ✅ **Error translation** - Converts cryptic provider errors into actionable messages.

## Governance & Security

mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn`.

### Governance Modes
- `:permissive` (Default): All tools are allowed unless explicitly denied.
- `:strict`: All tools are denied unless explicitly allowed in the policy.

### Privileged Tools
Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. These tools are **always blocked** by default, even in permissive mode, unless explicitly listed in an `:allow` rule.

### Example Policy
```clojure
:governance
{:mode :permissive
 :policy
 {:mode :permissive  ; Fallback mode for this policy (overrides global)
  :allow ["mcp__stripe__*"]
  :deny ["mcp__danger-server__*"]
  :rules [{:model "gpt-4o-mini" :deny ["clojure-eval"]}]
  :sampling {:trusted-servers ["stripe" "postgres"]}}
 :audit
 {:enabled true :path "logs/audit.log.ndjson"}
 :pii
 {:enabled true :mode :replace}}
```

## Quick Start

### Prerequisites
- [Babashka](https://babashka.org/) installed
- [Nix](https://nixos.org/) (optional)

### Installation
```bash
nix develop
bb test
bb run
```

## Configuration

Create `mcp-servers.edn`:
```clojure
{:servers
  {:stripe
   {:url "http://localhost:3001/mcp"
    :tools ["retrieve_customer" "list_charges"]}}
 
 :llm-gateway
 {:url "http://localhost:8080"
  :virtual-models
  {:brain
   {:chain ["provider1/model1" "provider2/model2"]
    :cooldown-minutes 5}}}}
```

## Control API

- `GET /api/v1/status`: Health and version.
- `GET /api/v1/mcp/tools`: List discovered tools.
- `GET /api/v1/audit/verify`: Cryptographically verify the audit log integrity.
- `POST /api/v1/mcp/reset`: Clear caches and restart processes.

## NixOS Deployment

```nix
services.mcp-injector = {
  enable = true;
  mcpServers = { ... };
  governance = {
    mode = "permissive";
    policy = {
      allow = [ "mcp__stripe__*" ];
    };
  };
};
```

______________________________________________________________________

**Status**: Production-ready | **Tests**: 37 passing | **Built with**: Babashka + http-kit + Cheshire
