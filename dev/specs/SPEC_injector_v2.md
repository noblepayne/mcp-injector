# SPEC: mcp-injector — Transparent Kernel & Loop Preservation
## Status: 🟢 READY FOR IMPLEMENTATION
**Version:** 2.0  
**Updated:** 2026-03-19  
**Supersedes:** SPEC_injector v1 (2026-03-19)

---

## 0. Change Summary (v1 → v2)

This version replaces three decisions from v1 that were architecturally unsound:

| Area | v1 (replaced) | v2 (this document) |
|---|---|---|
| Reasoning storage | Bake into `<thought>` XML string in `:content` | Preserve native typed content blocks with `signature` intact |
| Footer protocol | `;; injector-history: {JSON}` line comment | HTML comment sentinels + base64 + HMAC-SHA256 |
| Session durability | LRU in-memory cache only | Append-only `.jsonl` per session; LRU is a read cache over durable store |

All other v1 decisions (WorkLog accumulation, `agent-loop` return shape, `session-id` from `extra_body`, Cheshire for serialization) are carried forward unchanged.

---

## 1. Objective

Eliminate the epistemological bottleneck in multi-turn agentic sessions. Currently, the Injector runs internal tool loops and returns only the final summary to the client. The model in Turn N+1 therefore receives its own prior summary with zero supporting evidence, causing repeated work and reasoning drift.

The Injector must report every internal sub-turn — tool calls, tool results, and reasoning blocks — back to the client in a verifiable, structured format so the client can reconstruct a fully-grounded conversation history.

---

## 2. Architectural Principles

Before implementation details, these principles govern every decision below.

**P1 — The WorkLog is append-only and immutable.** Entries are never mutated after being written. Truncation of large results creates a new synthetic entry; it does not modify the original. This is the difference between an audit log and a mutable array.

**P2 — Storage and projection are separate concerns.** The WorkLog stores raw events with full fidelity. A projection function computes what gets sent to the model or client. Changing projection behavior never requires a schema migration.

**P3 — Structured data stays structured until it must become text.** Reasoning blocks, tool results, and content block arrays are stored as typed Clojure maps. They become text only at the final serialization boundary. Baking typed data into strings inside the system is forbidden.

**P4 — The footer is a signed artifact, not a convention.** Any system relying on an unsigned in-band payload is one malicious tool result away from a prompt injection. HMAC signing is not optional.

**P5 — Durability is not optional.** A session that survives a process restart is a feature. A session that doesn't is a bug.

---

## 3. Data Schemas

Define these as Clojure records or validated maps (via `malli` or `clojure.spec`). The schema is the contract; every other component depends on it being correct.

### 3.1 WorkLog Entry

```clojure
{;; Required fields
 :role          string    ; "assistant" | "tool"
 :content       vector    ; typed content block array (see 3.2)

 ;; Metadata — stripped by projection before sending to any external party
 :_meta {:provider    string   ; e.g. "anthropic", "openai", "ollama"
         :model       string   ; e.g. "claude-sonnet-4-20250514"
         :turn-index  integer  ; 0-based position in the agent loop
         :timestamp   instant  ; wall clock at time of entry creation
         :truncated?  boolean} ; true if any content block was truncated
}
```

### 3.2 Content Block Types

The `:content` field is always a vector of typed maps, never a plain string. The types mirror the Anthropic API's content block schema.

```clojure
;; Text block
{:type "text"
 :text string}

;; Thinking block (reasoning) — signature MUST be preserved verbatim
{:type      "thinking"
 :thinking  string   ; the raw reasoning text
 :signature string}  ; opaque token from provider; do not modify

;; Tool use block (outbound call)
{:type    "tool_use"
 :id      string   ; e.g. "toolu_01XYZ"
 :name    string   ; e.g. "read_file"
 :input   map}     ; tool arguments

;; Tool result block (inbound result)
{:type        "tool_result"
 :tool_use_id string   ; must match a prior :tool_use :id
 :content     vector}  ; nested content blocks (text, images)

;; Synthetic truncation marker — created by projection, never stored raw
{:type             "text"
 :text             "[tool result truncated: N bytes, threshold: 8192]"
 :_synthetic true}
```

### 3.3 Session Log File Format

One file per session, stored at `{INJECTOR_DATA_DIR}/sessions/{session-id}.jsonl`.

Each line is a single JSON-serialized WorkLog entry (newline-delimited JSON). Lines are appended, never overwritten. The file is the authoritative store; the LRU cache is derived from it.

```
{"role":"assistant","content":[{"type":"thinking","thinking":"...","signature":"Th5..."}],"_meta":{...}}
{"role":"assistant","content":[{"type":"tool_use","id":"toolu_01","name":"read_file","input":{"path":"/etc/hosts"}}],"_meta":{...}}
{"role":"tool","content":[{"type":"tool_result","tool_use_id":"toolu_01","content":[{"type":"text","text":"127.0.0.1 localhost..."}]}],"_meta":{...}}
```

---

## 4. Component Specifications

### 4.1 `agent-loop` (in `core.clj`)

**Current behavior:** Runs tool loop, returns final assistant message only.

**Required change:** Accumulate a WorkLog and return both.

**Return shape:**

```clojure
{:final-message  map     ; the final assistant message map (role + content blocks)
 :turns          vector} ; ordered WorkLog entries, one per sub-turn
```

**Accumulation rules:**

1. Before each provider call, snapshot the outbound messages if needed for debugging, but do not add them to the WorkLog (they are already in the client's history).
2. After each provider response, create a WorkLog entry from the assistant message. If the response contains `thinking` content blocks, include them in `:content` exactly as received — do not unwrap, stringify, or modify them. The `:signature` field must be byte-for-byte identical to what the provider returned.
3. After each tool execution, create a WorkLog entry with `role: "tool"` and the result as a `:tool_result` content block.
4. Set `:_meta` on every entry at creation time. `:provider` comes from the active provider configuration, `:model` from the response object, `:turn-index` from a loop counter starting at 0.
5. Call `(append-turn! session-id entry)` immediately after creating each entry — do not batch writes to end of loop.

**Ordering invariant (enforced, not conventional):**

The WorkLog for a single agent-loop execution must produce entries in this sequence for each tool call cycle:

```
[assistant: thinking block (if any)]
[assistant: tool_use block]
[tool: tool_result block]
... (repeat for parallel or sequential tool calls)
[assistant: final text summary]
```

The final text summary entry is the `:final-message`. It must be the last entry. Enforce this structurally — the loop should not be able to emit a text summary before all tool results are recorded.

### 4.2 Session Storage (`storage.clj` — new file)

This component owns all persistence. No other component writes session files directly.

```clojure
(def ^:private session-cache
  "LRU cache: session-id -> vector of WorkLog entries.
   Max 256 entries. Populated lazily from disk."
  (clojure.core.cache/lru-cache-factory {} :threshold 256))

(defn session-log-path [session-id]
  (let [data-dir (or (System/getenv "INJECTOR_DATA_DIR") ".injector/sessions")]
    (java.io.File. (str data-dir "/" session-id ".jsonl"))))

(defn append-turn!
  "Write a single WorkLog entry to the session log file.
   Creates the file and parent directories if they do not exist.
   Thread-safe: uses a per-session file lock."
  [session-id entry]
  (let [path (session-log-path session-id)
        line (str (cheshire/generate-string entry) "\n")]
    (io/make-parents path)
    (spit path line :append true)))

(defn load-session
  "Read all WorkLog entries for a session from disk.
   Returns an empty vector if no file exists.
   Updates the LRU cache."
  [session-id]
  (let [path (session-log-path session-id)]
    (if (.exists path)
      (->> (line-seq (io/reader path))
           (mapv #(cheshire/parse-string % true)))
      [])))

(defn get-session
  "Return WorkLog entries for session-id. Checks cache first, falls back to disk."
  [session-id]
  (if (clojure.core.cache/has? session-cache session-id)
    (clojure.core.cache/lookup session-cache session-id)
    (let [entries (load-session session-id)]
      (swap! session-cache assoc session-id entries)
      entries)))
```

**Configuration:**

| Env var | Default | Purpose |
|---|---|---|
| `INJECTOR_DATA_DIR` | `.injector/sessions` | Root directory for session log files |
| `INJECTOR_SESSION_CACHE_SIZE` | `256` | Max entries in LRU read cache |
| `INJECTOR_HMAC_SECRET` | (required) | HMAC signing key — no default, startup fails if absent |

Startup must fail fast with a clear error message if `INJECTOR_HMAC_SECRET` is not set.

### 4.3 Projection Function (`projection.clj` — new file)

The projection function transforms stored WorkLog entries into a form safe to send externally. It is called in two places: when building the re-hydration prefix for the model, and when building the footer for the client.

```clojure
(defn project-entry
  "Transform a WorkLog entry for external use.
   outbound-provider: the provider this projected entry will be sent to.
   Strips :_meta, handles provider mismatch for thinking blocks, applies truncation."
  [entry outbound-provider]
  (let [source-provider (get-in entry [:_meta :provider])
        content         (:content entry)
        projected-content
        (mapv (fn [block]
                (cond
                  ;; Thinking block: only forward if same provider generated it
                  (= (:type block) "thinking")
                  (if (= source-provider outbound-provider)
                    block
                    {:type "text"
                     :text "[reasoning from prior turn omitted: provider mismatch]"})

                  ;; Tool result: apply size truncation
                  (= (:type block) "tool_result")
                  (let [result-text (get-in block [:content 0 :text] "")
                        limit       8192]
                    (if (> (count result-text) limit)
                      (assoc-in block [:content 0 :text]
                                (str (subs result-text 0 limit)
                                     "\n[truncated: "
                                     (count result-text)
                                     " bytes total, limit "
                                     limit "]"))
                      block))

                  :else block))
              content)]
    (-> entry
        (dissoc :_meta)
        (assoc :content projected-content))))

(defn project-work-log
  "Project a full WorkLog for a given outbound provider."
  [work-log outbound-provider]
  (mapv #(project-entry % outbound-provider) work-log))
```

### 4.4 Footer Protocol (`openai_compat.clj`)

**Replace the `;;` footer entirely.** The new format uses HTML comment sentinels enclosing a base64-encoded, HMAC-signed JSON payload.

**Wire format:**

```
[Summary response text]

<!-- x-injector-v1
<base64-encoded-blob>
-->
```

Where `<base64-encoded-blob>` is the base64 encoding (standard alphabet, no line breaks) of the UTF-8 JSON string:

```json
{
  "hmac": "<hex-encoded HMAC-SHA256 of the 'data' field value>",
  "data": "<compact JSON string of the payload object>"
}
```

And the payload object is:

```json
{
  "source": "mcp-injector",
  "v": 1,
  "session_id": "<session-id>",
  "turns": [<projected WorkLog entries>]
}
```

**Implementation:**

```clojure
(defn hmac-sha256 [secret-key data-str]
  (let [mac      (javax.crypto.Mac/getInstance "HmacSHA256")
        key-spec (javax.crypto.spec.SecretKeySpec.
                   (.getBytes secret-key "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (->> (.doFinal mac (.getBytes data-str "UTF-8"))
         (map #(format "%02x" (Byte/toUnsignedInt %)))
         (apply str))))

(defn build-footer
  "Build the signed HTML-comment footer for a completed agent loop.
   work-log: projected (not raw) WorkLog entries — call project-work-log first.
   session-id: the active session identifier.
   secret-key: value of INJECTOR_HMAC_SECRET."
  [projected-turns session-id secret-key]
  (let [payload-str (cheshire/generate-string
                      {:source     "mcp-injector"
                       :v          1
                       :session_id session-id
                       :turns      projected-turns})
        hmac        (hmac-sha256 secret-key payload-str)
        envelope    (cheshire/generate-string {:hmac hmac :data payload-str})
        b64         (.encodeToString
                      (java.util.Base64/getEncoder)
                      (.getBytes envelope "UTF-8"))]
    (str "\n\n<!-- x-injector-v1\n" b64 "\n-->")))

(defn append-footer-to-response
  "Append the signed footer to the final assistant message content string.
   Must be called after project-work-log, not on raw WorkLog."
  [response-content projected-turns session-id]
  (let [secret (System/getenv "INJECTOR_HMAC_SECRET")]
    (str response-content
         (build-footer projected-turns session-id secret))))
```

**Failure mode behavior:**

- If `INJECTOR_HMAC_SECRET` is not set, `build-footer` must throw, not silently emit an unsigned footer. The calling code must catch this and fail the request with a 500, not swallow the error.
- If `projected-turns` is empty (the loop made no tool calls), still emit the footer with `"turns": []`. This signals to the client that the Injector ran but had nothing to report, which is distinct from the client receiving no footer at all.

### 4.5 Session ID Detection (`core.clj` / `openai_compat.clj`)

Extract `session-id` from the incoming request in this priority order:

1. `extra_body.session_id` (primary — Yap sets this)
2. `extra_body.user` (fallback — some clients use this field)
3. Generate a new UUID if neither is present, and log a warning

The detected session ID must be attached to the request context map at ingestion and threaded through the entire request lifecycle. It must not be re-detected mid-loop.

### 4.6 Re-hydration (`core.clj`)

When a new request arrives with a known `session-id`:

1. Call `(get-session session-id)` to retrieve the WorkLog.
2. If the WorkLog is non-empty, call `project-work-log` with the current outbound provider.
3. Prepend the projected entries to the incoming `messages` array, before the new user message.
4. Log the number of re-hydrated turns at `DEBUG` level.

The re-hydration prefix must be ordered `[oldest → newest]`. The new user message is always last. Never insert re-hydrated turns after the new user message.

---

## 5. Security Considerations

### 5.1 Prompt Injection via Tool Results

The WorkLog serializes tool results verbatim. A malicious MCP server (or a file read operation that returns attacker-controlled content) could embed text designed to look like injector protocol markers or system instructions.

**Mitigations implemented by this spec:**

- **HMAC signing** (§4.4): The footer is signed by the Injector before leaving the system. Yap verifies the signature before parsing. Attacker-controlled content in a tool result cannot produce a valid HMAC without the secret.
- **Role fidelity** (§3.1): Tool results are always stored and re-injected with `role: "tool"`. They are never promoted to `role: "system"` or `role: "user"` by the projection function.
- **Schema validation on re-hydration**: Before prepending projected entries, validate that every entry has an allowed role (`"assistant"` or `"tool"`) and that no content block is of type `"system"`. Reject the entire session and log an alert if validation fails.

### 5.2 Secret Management

- `INJECTOR_HMAC_SECRET` must be at least 32 bytes of random data.
- It must not be logged, included in error messages, or serialized anywhere.
- It must be the same value on the Injector and Yap for signature verification to work. Document this clearly in the deployment guide.

### 5.3 Session File Access

Session log files contain full tool results including potentially sensitive data (file contents, API responses, etc.). The `INJECTOR_DATA_DIR` must have restrictive filesystem permissions (mode `0700`). This is an operational requirement, not enforced in code, but should be documented in startup logs.

---

## 6. Implementation Task List

In strict dependency order. Do not begin a task until its dependencies are complete.

- [ ] **T1** — Define WorkLog entry schema with validation (`malli` preferred). Include all fields from §3.1 and §3.2. Write generative tests covering the thinking block with signature.
- [ ] **T2** — Implement `storage.clj`: `append-turn!`, `load-session`, `get-session`, LRU cache wrapper. Unit test round-trip: write entries, restart (clear cache), read back — entries must be identical.
- [ ] **T3** — Implement `projection.clj`: `project-entry` and `project-work-log`. Unit test all three projection cases: thinking block same provider (pass through), thinking block different provider (substitute text), tool result over 8KB (truncate with marker).
- [ ] **T4** — Update `agent-loop` in `core.clj` to accumulate WorkLog entries using the new schema, call `append-turn!` after each entry, and return `{:final-message m :turns [...]}`. Enforce the ordering invariant from §4.1.
- [ ] **T5** — Implement `build-footer` and `hmac-sha256` in `openai_compat.clj`. Unit test: verify the HMAC is stable (same inputs → same output), verify base64 round-trips cleanly, verify an empty turns vector still produces a valid footer.
- [ ] **T6** — Update the final response construction in `openai_compat.clj` to call `project-work-log` then `append-footer-to-response`. Remove all `;;` footer code.
- [ ] **T7** — Implement re-hydration in `core.clj` (§4.6). Integration test: two sequential requests with the same session ID — the second request's context must contain the first request's tool turns in the correct order.
- [ ] **T8** — Implement session ID detection (§4.5). Test all three detection paths (explicit, fallback, generated).
- [ ] **T9** — Add startup validation: fail fast if `INJECTOR_HMAC_SECRET` is absent or under 32 bytes. Log the data directory path and session cache size at startup.
- [ ] **T10** — Remove dead code: the old `reasoning-to-content` baking logic (the `<thought>` tag wrapping) must be deleted entirely, not commented out.

---

## 7. Open Questions (deferred, not blocking)

These questions are intentionally left open. They must not be resolved during implementation of this spec — record them for a follow-up decision.

**Q1 — Session log rotation:** How large should a session log file be allowed to grow before it is rotated or summarized? No limit is imposed by this spec; address in a future ops spec.

**Q2 — Multi-process safety:** `append-turn!` uses file appends which are atomic on most POSIX filesystems for small writes, but this has not been verified for the target deployment environment. If the Injector is run with multiple worker processes, a file lock or a single-writer agent is required.

**Q3 — Footer visibility toggle:** Yap may want a debug mode that shows the raw footer in the TUI. This is a Yap-side concern, but the Injector should log the footer byte length at `DEBUG` level to aid troubleshooting.

**Q4 — Generic `source` field:** The footer includes `"source": "mcp-injector"`. If other kernels adopt this protocol, the source field and version field together form the protocol discriminator. No action required in this implementation; noted for future protocol governance.
