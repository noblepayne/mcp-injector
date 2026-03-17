# mcp-injector

> "The Agency belongs in the Gateway."

`mcp-injector` is a **Minimal Agent Kernel** designed to sit between an Agent Gateway (like OpenClaw) and an LLM. It provides a secure, semi-transparent recursive tool loop for injected tools while delegating ultimate agency back to the Gateway.

## Core Philosophy

- **Minimal Kernel**: mcp-injector handles the recursive execution of its own tools (`mcp__*`, `clojure-eval`, `get_tool_schema`) but stays out of the way for everything else.
- **Strict Priority Handoff**: The kernel finishes its internal tool chain before handing off to the Gateway.
- **Smart PII Membrane**: Sensitive data is automatically redacted into session-scoped tokens before reaching the LLM, and restored only at the last second before tool execution or handoff.
- **Selective Pass-through Trust**: Trust levels for non-injected tools (owned by the Gateway) are managed via granular configuration.

## Selective Passthrough Handoff

The kernel executes internal tools and only hands off to the Gateway when a tool batch consists **exclusively** of external (passthrough) tools.

### The Decision Tree
1. If a tool batch contains **any** internal tool:
   - Kernel executes all internal tools.
   - Results are redacted (PII -> Tokens).
   - Conversation history is updated.
   - **Kernel recurs** (ignores passthrough tools in that turn).
2. If a tool batch is **exclusively passthrough**:
   - Kernel restores PII (Tokens -> Real values) based on trust rules.
   - Kernel **hands off** the request to the Gateway.

This ensures the Gateway always receives a complete, coherent context containing the results of all relevant internal tools.

## Pass-through Trust Governance

By default, the kernel trusts all tools (`:passthrough-trust :restore-all`). You can restrict this in your `mcp-servers.edn`:

```clojure
:governance
{:passthrough-trust
 {"read_file" :restore  ; Trusted (default)
  "exec" :block         ; Untrusted: received redacted tokens only
  "*" :restore}}        ; Wildcard support
```

Trust Levels:
- `:restore`: Swap `[CATEGORY_hash]` tokens back to original values in tool arguments.
- `:none`: Leave tokens redacted (Gateway sees the token).
- `:block`: Terminate the call if PII is detected in arguments.

## Governance & Security

mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn`.

### PII Restoration (Smart Vault)

For internal MCP tools that need access to original PII data (e.g., a Stripe integration that must see real email addresses), configure trust levels:

```clojure
:servers
{:stripe
 {:url "http://localhost:3001/mcp"
  :trust :restore  ; :none (default) or :restore
  :tools [{:name "retrieve_customer" :trust :restore}]}}
```

### ⚠️ Security Notice: `clojure-eval` Escape Hatch

The `clojure-eval` tool is a **privileged escape hatch** that allows the LLM to execute arbitrary Clojure code on the host JVM. This is **Remote Code Execution (RCE) by design**.

- **Default State**: Disabled. You must explicitly allow `clojure-eval` in your policy's `:allow` list.
- **Isolation**: clojure-eval is RCE-by-design; only enable it for fully trusted models in isolated environments.

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

## Control API

- `GET /api/v1/status`: Health and version.
- `GET /api/v1/mcp/tools`: List discovered tools in the current lattice.
- `GET /api/v1/stats`: Usage statistics and provider reliability.
- `GET /api/v1/audit/verify`: Cryptographically verify the audit log integrity.

______________________________________________________________________

**Status**: Production-ready | **Identity**: Minimal Agent Kernel | **Built with**: Babashka + http-kit
