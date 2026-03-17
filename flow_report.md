# Trust Levels: Read vs Restore

## Current Implementation

| Trust Level | Config | Actually Used? |
|-------------|--------|----------------|
| `:none` | Default | ✅ Yes (no restoration) |
| `:read` | Defined in config | ❌ **Not implemented** - behaves like `:none` |
| `:restore` | Full token restoration | ✅ Yes |
| `:block` | Block the tool | ✅ Yes |

## The Flow

```
LLM Request with PII
        ↓
┌─────────────────────────────────────────┐
│ scrub-messages (core.clj:243-249)       │
│ - pii/redact-data scans content         │
│ - Replaces PII with tokens:             │
│   "wes@email.com" → [EMAIL_ADDRESS_a35e]
│ - Stores original in vault              │
└─────────────────────────────────────────┘
        ↓
LLM sees redacted content
        ↓
┌─────────────────────────────────────────┐
│ LLM calls tool: mcp__stripe__charge    │
│ Args: {email: "[EMAIL_ADDRESS_a35e]"}   │
└─────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────┐
│ restore-tool-args (core.clj:251-256)    │
│                                         │
│ trust = config/get-server-trust(...)    │
│                                         │
│ if trust == :restore                    │
│    → pii/restore-tokens(args, vault)   │
│    → Replaces [EMAIL_ADDRESS_a35e]       │
│       with "wes@email.com"               │
│ else                                     │
│    → Sends tokens as-is                 │
└─────────────────────────────────────────┘
        ↓
Tool executes with REAL data (if restore)
or TOKEN data (if none)
        ↓
┌─────────────────────────────────────────┐
│ redact-tool-output (core.clj:258-282)   │
│ - Tool output is REDACTED again         │
│ - New PII in output gets new tokens    │
│ - Vault updated with new detections     │
└─────────────────────────────────────────┘
        ↓
┌─────────────────────────────────────────┐
│ scrub-messages (next iteration)         │
│ - All messages scrubbed again           │
│ - Vault carries forward PII seen        │
│   in this request                       │
└─────────────────────────────────────────┘
```

## Key Point: `:read` is Dead Code

Looking at `core.clj:254`, only `:restore` is checked:

```clojure
(if (= trust :restore)
  (pii/restore-tokens args vault)
  args)  ; :read falls through here, same as :none
```

The `:read` level is defined in config but never actually used. It's effectively a placeholder that behaves identically to `:none`.

## Summary

- **`:none`** (default) - Tokens stay as-is, tool receives `[EMAIL_ADDRESS_a35e]`
- **`:restore`** - Tokens restored, tool receives `wes@email.com`, output re-redacted
- **`:read`** - Exists but does nothing different from `:none` currently
