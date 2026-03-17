# ISSUE: PII Redaction Not Disabled by Governance Config

**Date:** 2026-03-16
**Status:** Resolved
**Priority:** High
**Area:** Governance / PII

## Summary

Setting `services.mcp-injector.governance.pii.enabled = false` in Nix config does NOT disable PII redaction. Redaction still occurs because the code hardcodes redaction without checking the config.

**Resolution (2026-03-16):** Fixed by updating `scrub-messages` and `redact-tool-output` to check `pii-enabled` flag from governance config. Also fixed `provided-governance` to check `base-mcp-servers` and `llm-gateway` locations. Added comprehensive integration tests.

## Root Cause Analysis

### Config Flow (broken)

```
Nix config (flake.nix)
  └── services.mcp-injector.governance.pii.enabled = false
        ↓ (via jet conversion to EDN)
  └── :llm-gateway {:governance {:pii {:enabled false}}}
        ↓ (config.clj resolve-governance)
  └── governance map with :pii {:enabled false}
        ↓ (unused!)
  └── CODE NEVER CHECKS IT
```

### Where Config Gets Lost

**Location 1:** `core.clj:244-246` - `scrub-messages`
```clojure
(let [config {:mode :replace :salt request-id}  ; hardcoded!
      [redacted-content new-vault _] (pii/redact-data content config current-vault)]
```

**Location 2:** `core.clj:259` - `redact-tool-output`
```clojure
(let [config {:mode :replace :salt request-id}  ; hardcoded!
```

Neither function receives or checks `governance` or `pii` config.

### What Should Happen

The governance config flows through to `agent-loop` (line 284):
```clojure
(defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
```

But it's never passed to `scrub-messages` or `redact-tool-output`.

## How This Happened

1. PII redaction was added as a feature
2. Config structure defined in `config.clj` with defaults
3. Nix module added with pii option
4. **No one connected the config to the code** - the governance map was passed to agent-loop but the functions that do redaction never used it

This is a classic "got it mostly working" scenario where:
- Config resolution works
- Default values work  
- But the actual usage in core.clj was forgotten

## Testing Gap

`test/mcp_injector/integration_test.clj` and other tests don't test PII config disabling. The redaction is assumed to work (or not) based on default behavior, not on config.

## Proposed Fix: Step-by-Step

### Fix 1: Update `scrub-messages` (core.clj:237-249)

**Current code:**
```clojure
(defn- scrub-messages [messages vault request-id]
  (reduce
   (fn [[msgs current-vault] m]
     (let [content (:content m)
           role (:role m)]
       (if (and (string? content)
                (contains? #{"system" "user" "assistant"} role))
         (let [config {:mode :replace :salt request-id}
               [redacted-content new-vault _] (pii/redact-data content config current-vault)]
           [(conj msgs (assoc m :content redacted-content)) new-vault])
         [(conj msgs m) current-vault])))
   [[] vault]
   messages))
```

**Fixed code:**
```clojure
(defn- scrub-messages [messages vault request-id governance]
  (let [pii-enabled (get-in governance [:pii :enabled] true)]
    (reduce
     (fn [[msgs current-vault] m]
       (let [content (:content m)
             role (:role m)]
         (if (and (string? content)
                  (contains? #{"system" "user" "assistant"} role)
                  pii-enabled)
           (let [config {:mode :replace :salt request-id}
                 [redacted-content new-vault _] (pii/redact-data content config current-vault)]
             [(conj msgs (assoc m :content redacted-content)) new-vault])
           [(conj msgs m) current-vault])))
     [[] vault]
     messages)))
```

**Changes:**
1. Add `governance` parameter
2. Extract `pii-enabled` with default `true`
3. Add `pii-enabled` check to the `if` condition

### Fix 2: Update `redact-tool-output` (core.clj:258-282)

**Current code:**
```clojure
(defn- redact-tool-output [raw-output vault request-id]
  (let [config {:mode :replace :salt request-id}
        parse-json (fn [s] (try (json/parse-string s true) (catch Exception _ nil)))
        parsed (parse-json raw-output)
        [redacted new-vault detected]
        (if parsed
          (let [parsed (cond ...)]
            [redacted-struct vault-after labels] (pii/redact-data parsed config vault)]
            [(json/generate-string redacted-struct) vault-after labels])
          (let [[redacted-str vault-after labels] (pii/redact-data raw-output config vault)]
            [redacted-str vault-after labels]))]
    (when (seq detected)
      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
    [redacted new-vault]))
```

**Fixed code:**
```clojure
(defn- redact-tool-output [raw-output vault request-id governance]
  (let [pii-enabled (get-in governance [:pii :enabled] true)
        config {:mode :replace :salt request-id}
        parse-json (fn [s] (try (json/parse-string s true) (catch Exception _ nil)))
        parsed (parse-json raw-output)
        [redacted new-vault detected]
        (if (and parsed pii-enabled)
          (let [parsed (cond ...)]
            [redacted-struct vault-after labels] (pii/redact-data parsed config vault)]
            [(json/generate-string redacted-struct) vault-after labels])
          (if pii-enabled
            (let [[redacted-str vault-after labels] (pii/redact-data raw-output config vault)]
              [redacted-str vault-after labels])
            [raw-output vault []]))]
    (when (and (seq detected) pii-enabled)
      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
    [redacted new-vault]))
```

**Changes:**
1. Add `governance` parameter
2. Extract `pii-enabled` with default `true`
3. Add `pii-enabled` check to both branches
4. When disabled, return original output unchanged with empty labels

### Fix 3: Update callers in `agent-loop`

Find all calls to `scrub-messages` and `redact-tool-output` and add `governance` argument.

**Around line 298:**
```clojure
[init-messages init-vault] (scrub-messages (:messages payload) vault request-id governance)]
```

**Around line 342:**
```clojure
[redacted updated-vault] (redact-tool-output raw-content vault-acc request-id governance)]
```

**Around line 369:**
```clojure
[scrubbed-messages post-vault] (scrub-messages new-messages new-vault request-id governance)]
```

### Fix 4: Fix Audit (same pattern)

**In core.clj around line 653**, where audit is called:
```clojure
(binding [*audit-config* (when (get-in governance [:audit :enabled] true)
                           {:path (io/file audit-log-path) :secret audit-secret})
```

## Research Questions

1. Are there other governance settings also not being checked? **YES - see below**
2. Should `governance` be a single param passed through, or extracted at the boundary?
3. What's the right pattern: "opt-out" (defaults to on) or "opt-in" (defaults to off)?

## Related Files

- `src/mcp_injector/core.clj` - lines 237-249, 258-282
- `src/mcp_injector/config.clj` - line 291 (defaults)
- `flake.nix` - lines 173-180 (nix option)
- `src/mcp_injector/pii.clj` - redact-data function

## Notes

- User tried `governance.pii.enabled = false` in Nix config
- Redaction still occurred
- This is blocking their work

---

## BONUS FINDING: Audit Also Not Checked

Same pattern - `governance.audit.enabled` is defined in defaults (config.clj:292) but NEVER checked in code. Audit runs regardless.

```clojure
;; config.clj:292 - defines the option
:audit {:enabled true :path (:audit-log-path env-config)}

;; core.clj:558-566 - just uses path/secret, ignores :enabled
(let [path (:audit-log-path config)
      secret (:audit-secret config)
      ...]
  (audit-conf {:path (io/file path) :secret secret} ...)
```

So both `pii.enabled` and `audit.enabled` are dead config - defined but never read.

---

## Proposed Tests: Full Implementation

### Test File: test/mcp_injector/governance_config_test.clj

Create new test file specifically for governance config behavior.

```clojure
(ns mcp-injector.governance-config-test
  "Tests for governance configuration: pii and audit enabled/disabled"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [mcp-injector.core :as core]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.test-llm-server :as test-llm]))

;; Test infrastructure
(def ^:dynamic *mcp* nil)
(def ^:dynamic *llm* nil)
(def ^:dynamic *injector* nil)

(defn test-fixture [test-fn]
  (let [mcp-server (test-mcp/start-test-mcp-server)
        llm-server (test-llm/start-server)
        injector (core/start-server {:port 0
                                      :host "127.0.0.1"
                                      :llm-url (str "http://localhost:" (:port llm-server))
                                      :mcp-servers {:servers {:test {:url (str "http://localhost:" (:port mcp-server))
                                                                     :tools [:get_user :query]}}
                                                    :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
    (binding [*mcp* mcp-server
              *llm* llm-server
              *injector* injector]
      (test-fn)
      (core/stop-server injector)
      (test-llm/stop-server llm-server)
      (test-mcp/stop-server mcp-server))))

(use-fixtures :once test-fixture)

(deftest pii-enabled-by-default-test
  "Verify PII redaction is enabled by default"
  (let [;; Configure with email in user message
        _ (test-llm/set-next-response *llm*
              {:role "assistant" :content "I see the email" :tool_calls nil})
        response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
                           {:body (json/generate-string
                                    {:model "test"
                                     :messages [{:role "user" :content "user: admin@example.com"}]})})
        body (:body response)
        llm-requests @(:received-requests *llm*)]

    ;; Check that the LLM received redacted content
    (is (pos? (count llm-requests)))
    (let [llm-req (json/parse-string (first llm-requests) true)
          user-msg (-> llm-req :messages last :content)]
      (is (str/includes? user-msg "EMAIL_ADDRESS_") 
          "Email should be redacted when PII enabled (default)"))))

(deftest pii-disabled-via-governance-test
  "When governance.pii.enabled is false, no redaction occurs"
  ;; Restart injector with pii disabled
  (core/stop-server *injector*)
  (let [injector (core/start-server {:port 0
                                      :host "127.0.0.1"
                                      :llm-url (str "http://localhost:" (:port *llm*))
                                      :mcp-servers {:servers {:test {:url (str "http://localhost:" (:port *mcp*))
                                                                     :tools [:get_user]}}
                                                    :llm-gateway {:url (str "http://localhost:" (:port *llm*))
                                                                 :governance {:pii {:enabled false}}}}})]
    (binding [*injector* injector]
      (let [;; Configure LLM to just echo back
            _ (test-llm/set-next-response *llm*
                  {:role "assistant" :content "Got it" :tool_calls nil})
            response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                               {:body (json/generate-string
                                        {:model "test"
                                         :messages [{:role "user" :content "user: admin@example.com"}]})})
            llm-requests @(:received-requests *llm*)]

        ;; Check that the LLM received ORIGINAL email, not redacted
        (is (pos? (count llm-requests)))
        (let [llm-req (json/parse-string (first llm-requests) true)
              user-msg (-> llm-req :messages last :content)]
          (is (str/includes? user-msg "admin@example.com")
              "Email should NOT be redacted when PII disabled")
          (is (not (str/includes? user-msg "EMAIL_ADDRESS_"))
              "Should not contain EMAIL_ADDRESS token")))
      (core/stop-server injector))))

(deftest pii-disabled-affects-tool-output-test
  "When governance.pii.enabled is false, tool output is not redacted"
  ;; Restart injector with pii disabled
  (core/stop-server *injector*)
  (let [injector (core/start-server {:port 0
                                      :host "127.0.0.1"
                                      :llm-url (str "http://localhost:" (:port *llm*))
                                      :mcp-servers {:servers {:test {:url (str "http://localhost:" (:port *mcp*))
                                                                     :tools [:get_user]
                                                                     :trust :restore}}
                                                    :llm-gateway {:url (str "http://localhost:" (:port *llm*))
                                                                 :governance {:pii {:enabled false}}}}})]
    ;; Configure test MCP to return email
    (test-mcp/set-tool-response *mcp* :get_user 
                                {:id 1 :email "secret@test.com" :name "Test User"})

    ;; Configure LLM to call tool
    (test-llm/set-next-response *llm*
          {:role "assistant" :content ""
           :tool_calls [{:type "function"
                         :function {:name "test__get_user"
                                    :arguments (json/generate-string {:id 1})}}]})
    ;; Then respond normally
    (test-llm/set-next-response *llm*
          {:role "assistant" :content "Got user" :tool_calls nil})

    (binding [*injector* injector]
      (let [response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
                               {:body (json/generate-string
                                        {:model "test"
                                         :messages [{:role "user" :content "get user 1"}]})})
            llm-requests @(:received-requests *llm*)]

        ;; Should see original email in tool output, not token
        (is (some #(str/includes? % "secret@test.com") llm-requests)
            "Tool output should have original email when PII disabled"))
      (core/stop-server injector))))
```

### Test Naming Convention

Follow existing pattern: `*_test.clj` suffix, namespace matches filename.

---

## Acceptance Criteria

- [ ] `governance.pii.enabled = false` results in NO redaction of user messages
- [ ] `governance.pii.enabled = false` results in NO redaction of tool outputs  
- [ ] Default behavior (pii.enabled = true) still works as before
- [ ] PII redaction still works when tool has `trust = "restore"` (restores original)
- [ ] Same tests for `governance.audit.enabled = false`