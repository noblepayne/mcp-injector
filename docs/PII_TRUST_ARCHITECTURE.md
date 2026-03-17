# PII Trust Architecture

## Trust Levels

| Trust Level | Config | Behavior |
|-------------|--------|----------|
| `:none` | Default | Tool receives redacted tokens (e.g., `[EMAIL_ADDRESS_a35e]`) |
| `:restore` | Full restoration | Tool receives original values (e.g., `wes@email.com`) |
| `:block` | Block tool | Tool fails if PII tokens detected in args |

## The Flow

```
LLM Request with PII
        ↓
┌─────────────────────────────────────────┐
│ scrub-messages                          │
│ - pii/redact-data scans content        │
│ - Replaces PII with tokens:            │
│   "wes@email.com" → [EMAIL_ADDRESS_a35e]
│ - Stores original in vault             │
└─────────────────────────────────────────┘
        ↓
LLM sees redacted content
        ↓
┌─────────────────────────────────────────┐
│ restore-tool-args                       │
│ trust = config/get-server-trust(...)   │
│                                         │
│ if trust == :restore                   │
│    → pii/restore-tokens(args, vault)  │
│    → Replaces token with original      │
│ else                                   │
│    → Sends tokens as-is                │
└─────────────────────────────────────────┘
        ↓
Tool executes (real data if restore, token if none)
        ↓
┌─────────────────────────────────────────┐
│ redact-tool-output                     │
│ - Tool output is REDACTED again        │
│ - New PII in output gets new tokens   │
│ - Vault updated                        │
└─────────────────────────────────────────┘
```

## Implementation Details

### Vault Architecture
- **Request-scoped**: Vault is created per-agent-loop and threaded through the `ctx` map
- **Salt**: Uses per-request `session-id` for deterministic SHA-256 hashing
- **Token Format**: `[LABEL_hash]` (e.g., `[EMAIL_ADDRESS_a35e2662]`)

### Observability
All duration measurements use monotonic `System/nanoTime` for sub-millisecond precision:
```clojure
(let [start-nano (System/nanoTime)
      result (do-work)
      duration (/ (- (System/nanoTime) start-nano) 1000000.0)]
  (log-request "debug" "Operation" {:duration-ms duration} ctx))
```

### Config Propagation
Config is resolved once at startup and threaded via the `ctx` map:
```clojure
(defn start-server [mcp-config]
  (let [eval-timeout-ms 5000  ; resolved once
        final-config {:eval-timeout-ms eval-timeout-ms ...}]
    (agent-loop ctx)))
```
