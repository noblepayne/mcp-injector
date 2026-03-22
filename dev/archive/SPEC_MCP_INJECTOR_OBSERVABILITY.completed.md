# SPEC: mcp-injector — Action Receipt + Observability Headers

**Status:** Ready for implementation  
**Priority:** High — active regression in production (footer visible in telegram/yap)  
**Estimated size:** ~120 lines new code, ~30 lines changed  
**Files touched:** `openai_compat.clj`, `core.clj`, `config.clj`  
**New test file:** `test/mcp_injector/receipt_test.clj`

---

## The Problem (Ground Truth)

The base64 footer blob (`<!-- x-injector-v1 ... -->`) is rendering as
visible text in yap's chat window — and in Telegram/openclaw it goes
straight to the user verbatim. This is a regression vs. not having the
injector at all. The prime directive is violated.

The footer was solving a real problem (operator visibility into what tools
ran) with the wrong mechanism (injecting opaque data into the LLM's
content field). The content field belongs to the LLM and the client.
We don't touch it with metadata.

The re-hydration system (`storage.clj` + `projection.clj`) already
correctly solves "the next LLM call understands what happened." That is
not what we're changing. This spec is only about surfacing what happened
to humans and smart clients, without degrading dumb clients.

---

## Prime Directive (encode this as a permanent regression test)

> A response from mcp-injector MUST NOT contain HTML comments, base64
> blobs, or injector metadata in `choices[0].message.content`.
> The content field belongs to the LLM and the client.

---

## What We Are Building

Two deliverables, in priority order:

### 1. The Action Receipt

A clean markdown block **prepended** to the response content when tools
were called during this request. Built deterministically from accumulated
tool execution data — no extra LLM call, no added latency, pure function.

**Example output (what the client sees at the top of content):**

```
🔧 3 tool calls · 842ms

`mcp__stripe__retrieve_customer` {"customer_id": "cus_123"…} ✓ 142ms
`mcp__postgres__query` {"sql": "SELECT * FROM users…"} ✓ 530ms
`mcp__chrome__submit` {} ✗ 211ms — timeout

---

[LLM's actual response follows here]
```

**Precise rules:**
- Prepend only when tools actually ran this request — zero tools = zero
  receipt = content passes through byte-for-byte identical
- Args: JSON-serialize the args map, truncate the whole string at 60
  chars with `…` appended. No per-arg logic. Just `subs`.
- Tool name: full `mcp__server__tool` format, in backticks
- Success: `✓ {ms}ms`
- Error: `✗ {ms}ms — {first line of error message}`. Errors are NEVER
  truncated — catching silent errors is the highest-value case
- Separator between receipt and LLM content: `\n\n---\n\n`
- Controlled by `:receipt-mode` config (see Configuration section)

### 2. Response Headers

Add to every `/v1/chat/completions` response. Always-on. Zero bytes
added to body. Every dumb client silently ignores unknown response
headers — this is load-bearing HTTP behaviour since RFC 2616.

```
X-Injector-Version: 1
X-Injector-Session: <session-id>
X-Injector-Turns: <n>
X-Injector-Tools: mcp__stripe__retrieve_customer,mcp__postgres__query
X-Injector-Ms: <total agent loop wall time ms>
```

`X-Injector-Tools`: comma-separated tool names actually called this
request (not discovered — called). Max 2000 chars, truncate with `...`
if exceeded. Empty string if no tools called.

---

## What We Are NOT Building in This Spec

- Session pull endpoint `GET /api/v1/session/{id}/turns` — noted for
  next spec, infrastructure is already there in `storage.clj`
- `X-Injector-Enrich` opt-in body enrichment — future
- LLM-generated summary turn — adds latency and failure modes, revisit later
- Any streaming protocol changes — receipt prepends to final content,
  which is already how the streaming path works today

---

## Configuration

Add to `default-config` in `config.clj`:

```clojure
:receipt-mode :on      ;; :on | :off | :errors-only
:receipt-style :emoji  ;; :emoji | :ascii
:footer-mode :off      ;; :off | :legacy (keep for transition)
```

**`:receipt-mode`**
- `:on` — prepend receipt when any tools ran (default)
- `:off` — no receipt, pure passthrough, content untouched
- `:errors-only` — prepend receipt only if at least one tool errored

**`:receipt-style`**
- `:emoji` — `✓` `✗` `🔧` (default, renders in Telegram/yap/terminals)
- `:ascii` — `OK` `ERR` `[TOOLS]` for 7-bit clean environments

**`:footer-mode`**
- `:off` — default, footer code exists but never runs
- `:legacy` — re-enables old footer for any workflow that depended on
  it during transition. Remove this option after yap migration is done.

**Environment variables:**
```
MCP_INJECTOR_RECEIPT_MODE=on|off|errors-only
MCP_INJECTOR_RECEIPT_STYLE=emoji|ascii
MCP_INJECTOR_FOOTER_MODE=off|legacy
```

**Per-request override:** if `extra_body` contains `{:receipt false}`,
suppress receipt for that request regardless of global config. Check
in `handle-chat-completion` before building content.

---

## Implementation

### New function: `build-receipt` in `openai_compat.clj`

This is a **calculation** — pure function, no side effects, no HTTP,
no state. Test it exhaustively before wiring it in.

Input shape for each entry:
```clojure
{:tool-name  "mcp__stripe__retrieve_customer"  ;; string
 :args-str   "{\"customer_id\": \"cus_123\"}"  ;; JSON string, may be long
 :success?   true                               ;; boolean
 :error-msg  nil                                ;; string or nil
 :ms         142}                               ;; integer
```

```clojure
(defn build-receipt
  "Build markdown action receipt from tool execution entries.
   Returns nil if no tools ran or mode is :off.
   Pure function — no side effects."
  [tool-entries {:keys [receipt-mode receipt-style]}]
  (when (and (seq tool-entries)
             (not= :off receipt-mode)
             (or (not= :errors-only receipt-mode)
                 (some #(not (:success? %)) tool-entries)))
    (let [emoji?    (not= :ascii receipt-style)
          check     (if emoji? "✓" "OK")
          cross     (if emoji? "✗" "ERR")
          wrench    (if emoji? "🔧 " "[TOOLS] ")
          total-ms  (reduce + (map :ms tool-entries))
          n         (count tool-entries)
          header    (str wrench n " tool call" (when (> n 1) "s")
                        " · " total-ms "ms\n\n")
          rows      (str/join "\n"
                     (map (fn [{:keys [tool-name args-str success?
                                       error-msg ms]}]
                            (let [safe-args  (or args-str "{}")
                                  displayed  (if (> (count safe-args) 60)
                                               (str (subs safe-args 0 60) "…")
                                               safe-args)
                                  status     (if success?
                                               (str check " " ms "ms")
                                               (str cross " " ms "ms"
                                                    (when error-msg
                                                      (str " — " error-msg))))]
                              (str "`" tool-name "` " displayed " " status)))
                          tool-entries))]
      (str header rows "\n\n---\n\n"))))
```

### New function: `build-response-headers` in `openai_compat.clj`

```clojure
(defn build-response-headers
  "Build X-Injector-* response headers map.
   Safe to call with nil — always returns at least version header."
  [{:keys [session-id turns tools-called ms]}]
  (cond-> {"X-Injector-Version" "1"}
    session-id
    (assoc "X-Injector-Session" (str session-id))
    (some? turns)
    (assoc "X-Injector-Turns" (str turns))
    ms
    (assoc "X-Injector-Ms" (str ms))
    (some? tools-called)
    (assoc "X-Injector-Tools"
           (let [s (str/join "," tools-called)]
             (if (> (count s) 2000)
               (str (subs s 0 1997) "...")
               s)))))
```

### Collecting tool execution data in the agent loop (`core.clj`)

The agent loop already has timing via `(System/nanoTime)` around tool
calls. Add a `tool-receipts` accumulator to the `loop` bindings.

In `execute-internal-batch`, after each `execute-tool` call, compute:
```clojure
{:tool-name  fn-name
 :args-str   (if (string? args-str) args-str (json/generate-string args))
 :success?   (not (contains? result :error))
 :error-msg  (when (contains? result :error)
               (let [msg (str (:error result))]
                 ;; First line only, max 80 chars
                 (subs msg 0 (min 80 (count (first (str/split-lines msg)))))))
 :ms         (long (/ (- (System/nanoTime) start-nano) 1000000))}
```

Return `tool-receipts` from `agent-loop` in its result map alongside
`:success`, `:data`, `:turns`, `:provider`.

### Wiring in `handle-chat-completion`

After `agent-loop` returns successfully:

```clojure
(let [tool-receipts   (:tool-receipts result [])
      suppress?       (false? (get-in chat-req [:extra_body :receipt] true))
      receipt-cfg     (select-keys config [:receipt-mode :receipt-style])
      receipt         (when-not suppress?
                        (build-receipt tool-receipts receipt-cfg))
      raw-content     (get-in final-resp [:choices 0 :message :content])
      final-content   (str (or receipt "") (or raw-content ""))
      obs-headers     (build-response-headers
                       {:session-id  session-id
                        :turns       (count tool-receipts)
                        :tools-called (map :tool-name tool-receipts)
                        :ms          total-elapsed-ms})]
  ;; Merge obs-headers into the existing headers map when building response
  {:status 200
   :headers (merge {"Content-Type" "application/json"} obs-headers)
   :body (json/generate-string ...)})
```

Note: `total-elapsed-ms` — record `(System/currentTimeMillis)` at the
top of `handle-chat-completion` and compute diff before building response.

### Disabling the footer

In `handle-chat-completion`, find the `build-footer` call and gate it:

```clojure
;; BEFORE (current):
(let [footer (openai/build-footer projected-new-turns pii-salt hmac-secret)
      final-content (str (or content "") footer)]
  ...)

;; AFTER:
(let [legacy-footer (when (= :legacy (:footer-mode config))
                      (openai/build-footer projected-new-turns pii-salt hmac-secret))
      final-content (str (or receipt "")
                         (or content "")
                         (or legacy-footer ""))]
  ...)
```

Default config has `:footer-mode :off` so `legacy-footer` is nil.
`build-footer` function body stays in `openai_compat.clj` — do not
delete it yet.

---

## Acceptance Tests

Write these before touching implementation. Add unit tests to the new
`test/mcp_injector/receipt_test.clj`. Add integration tests to the
existing `test/mcp_injector/integration_test.clj`.

### Unit tests — `receipt_test.clj`

```clojure
(ns mcp-injector.receipt-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [mcp-injector.openai-compat :as openai]))

(def cfg-on   {:receipt-mode :on   :receipt-style :emoji})
(def cfg-off  {:receipt-mode :off  :receipt-style :emoji})
(def cfg-err  {:receipt-mode :errors-only :receipt-style :emoji})
(def cfg-asc  {:receipt-mode :on   :receipt-style :ascii})

(def one-success
  [{:tool-name "mcp__stripe__retrieve_customer"
    :args-str  "{\"customer_id\": \"cus_123\"}"
    :success?  true :error-msg nil :ms 142}])

(def one-error
  [{:tool-name "mcp__chrome__submit"
    :args-str  "{}"
    :success?  false :error-msg "timeout after 5000ms" :ms 211}])

(deftest no-tools-returns-nil
  (is (nil? (openai/build-receipt [] cfg-on)))
  (is (nil? (openai/build-receipt nil cfg-on))))

(deftest mode-off-returns-nil
  (is (nil? (openai/build-receipt one-success cfg-off))))

(deftest errors-only-suppresses-when-all-succeed
  (is (nil? (openai/build-receipt one-success cfg-err))))

(deftest errors-only-shows-when-error-present
  (is (some? (openai/build-receipt one-error cfg-err))))

(deftest single-success-contains-expected-parts
  (let [r (openai/build-receipt one-success cfg-on)]
    (is (string? r))
    (is (str/starts-with? r "🔧"))
    (is (str/includes? r "mcp__stripe__retrieve_customer"))
    (is (str/includes? r "✓"))
    (is (str/includes? r "142ms"))
    (is (str/ends-with? r "---\n\n"))))

(deftest error-shows-message-untruncated
  (let [r (openai/build-receipt one-error cfg-on)]
    (is (str/includes? r "✗"))
    (is (str/includes? r "timeout after 5000ms"))))

(deftest args-truncated-at-60-chars
  (let [long-args (str "{\"key\": \"" (apply str (repeat 80 "x")) "\"}")
        entries [{:tool-name "mcp__foo__bar" :args-str long-args
                  :success? true :error-msg nil :ms 10}]
        r (openai/build-receipt entries cfg-on)
        ;; find the row line (second non-empty line)
        row-line (->> (str/split-lines r)
                      (remove str/blank?)
                      second)]
    (is (str/includes? row-line "…"))
    ;; args display section is at most 61 chars (60 + ellipsis)
    (let [args-section (second (re-find #"`[^`]+` (.+) [✓✗]" row-line))]
      (is (<= (count args-section) 61)))))

(deftest nil-args-handled-gracefully
  (let [entries [{:tool-name "mcp__foo__bar" :args-str nil
                  :success? true :error-msg nil :ms 10}]]
    (is (some? (openai/build-receipt entries cfg-on)))))

(deftest ascii-style-no-emoji
  (let [r (openai/build-receipt one-success cfg-asc)]
    (is (str/includes? r "OK"))
    (is (str/includes? r "[TOOLS]"))
    (is (not (str/includes? r "✓")))
    (is (not (str/includes? r "🔧")))))

(deftest header-count-and-total-ms
  (let [entries [{:tool-name "a" :args-str "{}" :success? true :ms 100}
                 {:tool-name "b" :args-str "{}" :success? true :ms 200}
                 {:tool-name "c" :args-str "{}" :success? false
                  :error-msg "err" :ms 50}]
        r (openai/build-receipt entries cfg-on)]
    (is (str/includes? r "3 tool calls"))
    (is (str/includes? r "350ms"))))

(deftest build-response-headers-full
  (let [h (openai/build-response-headers
           {:session-id "abc123" :turns 3
            :tools-called ["mcp__stripe__foo" "mcp__postgres__bar"]
            :ms 842})]
    (is (= "1"      (get h "X-Injector-Version")))
    (is (= "abc123" (get h "X-Injector-Session")))
    (is (= "3"      (get h "X-Injector-Turns")))
    (is (= "842"    (get h "X-Injector-Ms")))
    (is (str/includes? (get h "X-Injector-Tools") "mcp__stripe__foo"))
    (is (str/includes? (get h "X-Injector-Tools") "mcp__postgres__bar"))))

(deftest build-response-headers-nil-safe
  (let [h (openai/build-response-headers nil)]
    (is (map? h))
    (is (= "1" (get h "X-Injector-Version"))))
  (let [h (openai/build-response-headers {})]
    (is (= "1" (get h "X-Injector-Version")))))

(deftest build-response-headers-tools-truncated
  (let [many-tools (map #(str "mcp__server__tool" %) (range 500))
        h (openai/build-response-headers {:tools-called many-tools})]
    (is (<= (count (get h "X-Injector-Tools")) 2003)))) ;; 2000 + "..."
```

### Integration tests — add to `integration_test.clj`

```clojure
;; THE PRIME DIRECTIVE — never remove this test
(deftest prime-directive-no-injector-artifacts-in-content
  (testing "Content must never contain HTML comments or base64 blobs"
    (test-llm/set-tool-call-response *test-llm*
      [{:name "mcp__stripe__retrieve_customer"
        :arguments {:customer_id "cus_123"}}])
    (test-llm/set-next-response *test-llm*
      {:role "assistant" :content "Found the customer."})
    (let [resp @(http/post (str "http://localhost:" (:port *injector*)
                                "/v1/chat/completions")
                           {:body (json/generate-string
                                   {:model "test"
                                    :messages [{:role "user"
                                                :content "find customer"}]})
                            :headers {"Content-Type" "application/json"}})
          content (get-in (json/parse-string (body->string (:body resp)) true)
                          [:choices 0 :message :content])]
      (is (= 200 (:status resp)))
      (is (not (str/includes? content "x-injector-v1"))
          "No injector footer sentinel in content")
      (is (not (str/includes? content "<!--"))
          "No HTML comments in content")
      (is (not (re-find #"[A-Za-z0-9+/]{60,}={0,2}" content))
          "No base64 blobs in content"))))

(deftest receipt-prepended-when-tools-ran
  (testing "Receipt appears at top, LLM content follows after separator"
    (test-llm/set-tool-call-response *test-llm*
      [{:name "mcp__stripe__retrieve_customer"
        :arguments {:customer_id "cus_123"}}])
    (test-llm/set-next-response *test-llm*
      {:role "assistant" :content "Found the customer."})
    (let [resp @(http/post ...)
          content (get-in (json/parse-string (body->string (:body resp)) true)
                          [:choices 0 :message :content])]
      (is (str/starts-with? content "🔧"))
      (is (str/includes? content "mcp__stripe__retrieve_customer"))
      (is (str/includes? content "Found the customer."))
      (is (< (.indexOf content "🔧")
             (.indexOf content "Found the customer."))))))

(deftest no-receipt-when-no-tools-ran
  (testing "No tools = content is byte-for-byte what the LLM returned"
    (test-llm/set-next-response *test-llm*
      {:role "assistant" :content "Just a direct answer."})
    (let [resp @(http/post ...)
          content (get-in (json/parse-string (body->string (:body resp)) true)
                          [:choices 0 :message :content])]
      (is (= "Just a direct answer." (strip-footer content)))
      (is (not (str/includes? content "🔧"))))))

(deftest response-headers-always-present
  (testing "X-Injector-* headers on every response including no-tool responses"
    (test-llm/set-next-response *test-llm*
      {:role "assistant" :content "Hello."})
    (let [resp @(http/post ...)
          headers (:headers resp)]
      (is (some #(= "x-injector-version" (str/lower-case (name %)))
                (keys headers))))))

(deftest receipt-suppressed-via-extra-body
  (testing "extra_body {:receipt false} suppresses receipt for that request"
    (test-llm/set-tool-call-response *test-llm*
      [{:name "mcp__stripe__retrieve_customer"
        :arguments {:customer_id "cus_123"}}])
    (test-llm/set-next-response *test-llm*
      {:role "assistant" :content "Found customer."})
    (let [resp @(http/post (str "http://localhost:" (:port *injector*)
                                "/v1/chat/completions")
                           {:body (json/generate-string
                                   {:model "test"
                                    :messages [{:role "user" :content "find"}]
                                    :extra_body {:receipt false}})
                            :headers {"Content-Type" "application/json"}})
          content (get-in (json/parse-string (body->string (:body resp)) true)
                          [:choices 0 :message :content])]
      (is (not (str/includes? content "🔧"))))))
```

---

## Implementation Stages

Each stage is independently testable. Do not proceed to next stage
until current stage tests are green.

**Stage 1 — Pure functions only (no server changes)**
Write `receipt_test.clj`. Implement `build-receipt` and
`build-response-headers` in `openai_compat.clj`. Run unit tests until
all pass. Nothing in core.clj or config.clj changes yet.

**Stage 2 — Wire headers (no content change yet)**
Add `build-response-headers` call in `handle-chat-completion`. Merge
into response `:headers` map. Add `response-headers-always-present`
integration test. Verify with curl that headers appear.

**Stage 3 — Collect tool execution data**
Add `tool-receipts` accumulator to agent loop. Collect entries in
`execute-internal-batch` (already has timing logic, extend it). Return
receipts in result map. Wire `build-receipt` into content prepend in
`handle-chat-completion`. Add receipt integration tests.

**Stage 4 — Disable footer by default**
Gate `build-footer` behind `:footer-mode :legacy`. Default `:off`.
Add prime directive integration test. Manual curl smoke test: confirm
no HTML comments in content.

**Stage 5 — Config and per-request override**
Add env var parsing in `config.clj` for `MCP_INJECTOR_RECEIPT_MODE`
etc. Add `extra_body {:receipt false}` check. Add suppression test.

---

## Edge Cases

| Case | Handling |
|------|----------|
| Tool args are nil | `(or args-str "{}")` before truncation |
| Tool args are a map not a string | `(json/generate-string args)` in accumulator |
| Tool errored, LLM didn't mention it | Receipt shows `✗` — this is the point |
| PII tokens in args display | Fine — token shape is useful for operator |
| Max iterations hit | Receipt shows all tools that ran before limit |
| content is nil (tool-call-only response) | `(str (or receipt "") (or content ""))` |
| Streaming mode | Receipt prepends to content before SSE chunks — no change needed |
| extra_body is nil | `(get-in chat-req [:extra_body :receipt] true)` handles it |

---

## Done When

- [ ] All unit tests in `receipt_test.clj` pass
- [ ] Prime directive integration test in `integration_test.clj` passes
- [ ] Receipt appears correctly in manual curl test with tool calls
- [ ] Receipt absent in manual curl test with no tool calls  
- [ ] `X-Injector-*` headers present on all responses (verified with curl -v)
- [ ] Footer disabled by default, no HTML comments in any response
- [ ] `extra_body {:receipt false}` suppresses receipt
- [ ] `MCP_INJECTOR_RECEIPT_MODE=off` env var works
- [ ] All pre-existing tests still pass
- [ ] `clj-kondo --lint src/ test/` clean
- [ ] `cljfmt check src/ test/` clean
