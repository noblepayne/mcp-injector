# Spec: Hardened Governance Config & Trust Levels

## 1. Problem Statement
The current governance implementation has "dead" configuration:
- `governance.pii.enabled` is defined but ignored (PII redaction is always on).
- `governance.audit.enabled` is defined but ignored (Audit logging is always on).
- The `:read` trust level is defined in config but unimplemented in the agent loop.
- There is no `:block` trust level to prevent tools from even seeing tokens.

## 2. Proposed Changes

### Configuration Refactor
- **Remove**: `:read` trust level (redundant/ambiguous).
- **Add**: `:block` trust level (fails the tool call if PII tokens are present in arguments).
- **Default**: `:none` (tokens only, no restoration).

### Functional Hardening
- **Check Enabled Flags**: `scrub-messages` and `redact-tool-output` must check `governance.pii.enabled`.
- **Audit Toggle**: `execute-tool` must check `governance.audit.enabled`.
- **Mandatory Output Redaction**: All tool outputs are re-redacted by default unless PII is globally disabled.

## 3. Implementation Plan

### Phase 1: Reproduction & Baseline (Tests First)
1. **Create `test/mcp_injector/governance_test.clj`**:
   - `test-pii-disabled`: Set `governance.pii.enabled = false` and verify raw email reaches LLM.
   - `test-audit-disabled`: Set `governance.audit.enabled = false` and verify no new lines in audit log.
   - `test-trust-block`: Set `trust :block` for a tool and verify it returns a 403-style error when tokens are passed.

### Phase 2: Wiring & Logic (core.clj)
1. **Pass Governance Map**:
   - Update `handler` to extract `:governance` from `final-config`.
   - Update `agent-loop` to accept and pass `governance` to its sub-functions.
2. **Implement Toggle Checks**:
   - Modify `scrub-messages` to return original messages if `:pii :enabled` is false.
   - Modify `redact-tool-output` to return raw output if `:pii :enabled` is false.
   - Modify `execute-tool` to skip `audit/log-event!` if `:audit :enabled` is false.
3. **Implement `:block` Trust**:
   - Update `restore-tool-args` to scan for tokens if trust is `:block`.
   - If tokens found, throw an exception or return an error map immediately.

### Phase 3: Verification & Cleanup
1. **Run All Tests**: `bb test` must show 0 failures across all suites.
2. **Update Docs**: Reflect the removal of `:read` and addition of `:block` in `README.md` and `mcp-servers.example.edn`.
3. **Final Audit**: Verify `clojure-eval` still defaults to `:none` and cannot see real secrets.

## 4. Instructions for Future Agent
> **IMPORTANT**: Follow the steps in "Implementation Plan" exactly. 
> 1. Start by creating the `governance_test.clj` to confirm the existing bugs.
> 2. Do NOT implement `:read`; remove it from any existing config parsing.
> 3. Ensure `redact-tool-output` is ALWAYS called if PII is enabled, even for `:restore` tools.
