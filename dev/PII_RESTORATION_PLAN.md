# PII Restoration & Secret Substitution Plan

## Status
- **Branch:** `feat/pii-restoration`
- **Current State:** Reverted to stable baseline. Previous attempt at full-file rewrites failed due to regression loss.
- **Next Action:** Surgical, incremental implementation of the "Vault" pattern.

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

## Incremental Build Plan

### Step 1: `pii.clj` Enhancements
- [ ] Add `generate-token [label value salt]` (SHA-256 truncated).
- [ ] Add `redact-data [data config]` (The walking structural redactor).
- [ ] Add `restore-tokens [data vault]` (The inverse walking restorer).
- [ ] **Verification:** `bb test` (unit tests for pii namespace).

### Step 2: `config.clj` Trust Resolution
- [ ] Add `get-server-trust [mcp-config server-name tool-name]`.
- [ ] Support `:trust :restore` at server and individual tool levels.

### Step 3: `core.clj` Agent Loop Integration
- [ ] Initialize `vault (atom {})` inside `agent-loop`.
- [ ] Thread vault through `scrub-messages`.
- [ ] Intercept `tool_calls`:
    - Restore args if trusted.
    - Execute tool.
    - Redact output and merge new tokens into vault.
- [ ] **Verification:** Existing integration tests must pass first.

### Step 4: Full Integration Test
- [ ] Fix `test/mcp_injector/restoration_test.clj`.
- [ ] Verify: Tool -> Redact -> LLM -> Decide -> Restore -> Tool (Real Value).

## Guidelines
- **No `cat` rewrites:** Use surgical edits only.
- **Test First:** Run `bb test` after every functional change.
- **Fail Closed:** Default trust is always `:none`.
