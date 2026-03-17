# Changelog

All notable changes to mcp-injector will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v0.2.0] - 2026-03-17

### Added
- **PII Restoration (Smart Vault)**: Request-scoped vault for token→original value mapping with trust levels (:none, :restore)
- **Runtime Hardening**: 5s clojure-eval timeout, 20-level recursion depth limit
- **PII Patterns**: Expanded detection for AWS keys, GitHub tokens, Stripe keys, DB URLs, Slack webhooks, private keys
- **Observability**: Monotonic timing (nanoTime) with Double precision duration-ms
- **Audit Improvements**: Once-only stderr warning when audit not initialized

### Fixed
- **Governance Config**: Fixed dead config where pii.enabled and audit.enabled were ignored
- **Nix Parity**: Added evalTimeoutMs option to flake.nix

### Changed
- **Virtual Model Retry**: Default retry-on expanded to [400-404, 429, 500, 503]

---

## [v0.1.0] - 2026-02-12

### Added
- Initial release
- MCP server integration
- Basic governance framework
- PII scanning and redaction
