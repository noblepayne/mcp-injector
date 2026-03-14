# PII Restoration & Secret Substitution Plan

## Status
- **Branch:** `feat/pii-restoration`
- **Current State:** COMPLETE - All tests passing
- **Completed:** 2026-03-14

## Summary
Successfully implemented "Smart Vault" PII restoration system:
- Deterministic token generation with SHA-256
- Request-scoped vault for token/value mapping
- Trust levels (`:none`, `:read`, `:restore`) at server and tool level
- Two-way substitution: LLM sees tokens, trusted tools see real values

## Core Strategy: The "Smart Vault"
We are moving from "One-Way Redaction" to "Two-Way Substitution" for trusted tools.

### 1. Tokenization (Outbound to LLM)
- **Deterministic Hashing:** Replace `[EMAIL_ADDRESS]` with `[PII_EMAIL_8ce0db03]`. 
- **Vaulting:** Store `{ "[PII_EMAIL_8ce0db03]" "wes@example.com" }` in a request-scoped vault.
- **Structural Awareness:** Use `clojure.walk` to redact JSON values while preserving keys (so LLM understands schema).

### 2. Restoration (Inbound to Tools)
- **Trust Tiers:** Define `:trust :restore` in `mcp-servers.edn`.
- **Restoration:** If a tool call targets a trusted server, `mcp-injector` swaps tokens back for real values before execution.
- **Safety:** Untrusted tools continue to see only the redacted tokens.

## Build Results
- **55 tests** - All passing
- **Lint** - Clean
- **Format** - Clean
