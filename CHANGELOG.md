# Changelog

All notable changes to mcp-injector will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- (none)

### Changed
- (none)

### Fixed
- (none)

---

## [0.x] - 2026-03-13

### Added
- **Provider-Level Usage Tracking**: Dual-track usage statistics for both model aliases and underlying providers (rate limits, context overflows, token counts)
- **Self-Monitoring Infrastructure**: `/api/v1/stats` endpoint for downstream agents to monitor consumption and provider reliability
- **Tool Output Sanitization**: Prevents structural prompt injection in tool results
- **Generic Security Errors**: Policy denial returns generic "Tool execution denied" to prevent fingerprinting
- **Governance Framework**: Declarative tool access policies (Permissive/Strict modes)
- **Privileged Tools**: High-risk tools like `clojure-eval` blocked by default, require explicit allow rule
- **PII Scanning**: Automatic redaction of sensitive data in prompts and tool outputs
- **Signed Audit Trail**: Tamper-proof NDJSON logs with ULID and HMAC chaining
- **JSON-RPC Interceptor**: Infrastructure for Sampling protocol support
- **NixOS Module**: Full governance, MCP server, and environment configuration support

### Changed
- **Virtual Model Retry Strategy**: 503 (context overflow) excluded from retry - same model = same context window
- **Configuration**: Split into `mcp-servers.example.edn` (template) and local `mcp-servers.edn` (gitignored)

### Fixed
- **Stateless StreamableHTTP**: Support for MCP servers without session ID
- **Error Translation**: Better context overflow detection and error messaging
- **Header Normalization**: Keyword headers from Nix config properly handled

---

## [0.1.0] - 2026-02-17

### Added
- **MVP Core**: HTTP shim between OpenClaw and LLM gateways
- **Virtual Model Chains**: Fallback providers with cooldowns
- **MCP Tool Injection**: Support for HTTP and STDIO transports
- **Tool Discovery**: Runtime auto-discovery of MCP server tools
- **Error Handling**: Rate limit, timeout, and connection failure handling
- **Nix Development**: Flake-based reproducible environment

---

## [0.0.1] - 2026-02-12

### Added
- Initial project setup
- Basic HTTP server with OpenAI-compatible API
- SSE streaming support

---

## Legacy Notes

### Naming History
- **mcp-injector**: Current name (was "situated-agent-runtime" in early planning)

### Feature Phases
- **Phase 1**: Core Runtime - HTTP shim, MCP tool injection, virtual models
- **Phase 2**: Governance - PII redaction, audit trail, tool policies
- **Phase 3**: Advanced - Sampling protocol, SCI for sandboxing, glob patterns

---

*This changelog was generated from git history on 2026-03-13.*
