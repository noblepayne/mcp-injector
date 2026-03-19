# PII Scanner False Positive Reduction

## Context

The PII scanner in `mcp-injector` currently flags high-entropy strings without context awareness, causing false positives on:
- File paths: `/skills/agent-tunnel/`
- URLs: `http://5.78.100.114:19992`
- Skill descriptions (documentation)
- SHA hashes and content identifiers

This makes injected instructions unreadable for downstream agents.

## Research Summary

Industry best practices (GitGuardian, TruffleHog, GitHub) use **two-layer defense**:

1. **Safe patterns** - Skip known-safe formats before entropy check
2. **Proximity check** - Only flag entropy tokens following assignment keywords

### GitGuardian's Generic High Entropy Detector

Requires BOTH:
1. High entropy value (entropy > 3.0)
2. **Assignment to sensitive variable** (`secret`, `token`, `api_key`, `credential`, `auth`)

---

## Solution Design

### Phase 1: Safe Pattern Whitelist

Skip entropy detection for tokens matching known-safe patterns:

```clojure
;; File paths: contains / or \ with only path-safe characters
;; Examples: /skills/agent-tunnel/, /home/user/.ssh/id_rsa

;; URLs: starts with protocol
;; Examples: http://5.78.100.114:19992, https://api.example.com

;; IP addresses: standalone or in URLs (ALWAYS whitelist per user request)
;; Examples: 5.78.100.114, 192.168.1.1, 10.0.0.1:8080

;; Hex-only SHA hashes: 32+ chars, lowercase hex only
;; Examples: a1b2c3d4e5f6789012345678901234567890abcd

;; UUIDs: standard format
;; Examples: 550e8400-e29b-41d4-a716-446655440000
```

### Phase 2: Proximity-Based Assignment Detection

**ON by default but configurable** - Only flag entropy tokens following assignment patterns.

```clojure
;; Assignment patterns (25 char window before token):
;; - secret = xxx
;; - api_key: xxx  
;; - token := xxx
;; - password => xxx
;; - credential <- xxx
;; - auth(xxx)
```

**Config options:**
```clojure
{:proximity-enabled? true   ;; default: true
 :proximity-window 25       ;; chars to check before token
 :safe-patterns-enabled? true}
```

### IP Address Handling

**Always whitelist** - IP addresses with or without URL context are skipped.

---

## Test Design (TDD)

### Test Categories

#### 1. Regression Tests (Existing Behavior Must Not Break)

```clojure
(deftest regression-high-entropy-secrets-test
  "Existing high-entropy secret detection must continue working"
  (testing "sk-proj keys should still be caught")
  (testing "Bare passwords should still be caught")
  (testing "Complex auth strings should still be caught"))
```

#### 2. Safe Pattern Tests (Phase 1)

```clojure
(deftest safe-patterns-file-paths-test
  (testing "Unix file paths")
  (testing "Windows file paths")
  (testing "Nested directory paths")
  (testing "Paths with mixed case and numbers"))

(deftest safe-patterns-urls-test
  (testing "HTTP URLs with ports")
  (testing "HTTPS URLs")
  (testing "URLs with query parameters")
  (testing "IP addresses with ports"))

(deftest safe-patterns-ip-addresses-test
  "IP addresses should ALWAYS be whitelisted (per user request)"
  (testing "Standalone IPv4 addresses")
  (testing "IPv4 with port numbers")
  (testing "IPv4 in various contexts (skill docs, URLs, etc.)")
  (testing "IPv6 addresses"))

(deftest safe-patterns-hashes-test
  (testing "SHA-256 hashes (64 hex chars)")
  (testing "SHA-1 hashes (40 hex chars)")
  (testing "Short hex strings should still be caught if diverse"))

(deftest safe-patterns-uuids-test
  (testing "Standard UUID format")
  (testing "UUID without hyphens"))
```

#### 3. Proximity Assignment Tests (Phase 2)

```clojure
(deftest proximity-assignment-test
  (testing "Secrets following assignment keywords")
  (testing "Secrets NOT following assignment should be skipped")
  (testing "Proximity window should be ~25 chars")
  (testing "Various assignment syntaxes"))
```

#### 4. Real-World False Positive Tests

```clojure
(deftest real-world-false-positives-test
  "Tests from actual mcp-injector usage"
  (testing "Skill descriptions should not be redacted")
  (testing "MCP tool names should not be redacted")
  (testing "Markdown code blocks content"))
```

#### 5. Edge Case Tests

```clojure
(deftest edge-cases-test
  (testing "Token at start of string")
  (testing "Token at end of string")
  (testing "Multiple tokens in same string")
  (testing "Mixed safe patterns and real secrets"))
```

---

## Implementation Plan

### Step 1: Add Helper Functions

```clojure
;; pii.clj - Add these functions

(defn- safe-pattern?
  "Check if token matches known-safe patterns (paths, URLs, hashes, UUIDs)"
  [token]
  ...)

(defn- likely-secret-context?
  "Check if token follows assignment-like patterns"
  [text start-pos]
  ...)
```

### Step 2: Modify find-all-coordinates

Update entropy detection to use new guards:

```clojure
;; In find-all-coordinates, modify entropy-matches:
(when (and (> (shannon-entropy token) entropy-threshold)
           (character-diversity? token)
           (not (safe-pattern? token))           ;; Phase 1
           (likely-secret-context? text end))    ;; Phase 2
  ...)
```

### Step 3: Make Proximity Configurable

Add optional configuration for advanced users:

```clojure
;; Config options:
;; :proximity-enabled? - Enable proximity check (default: true)
;; :proximity-window - Characters to check before token (default: 25)
;; :safe-patterns-enabled? - Enable safe pattern filtering (default: true)
```

---

## Acceptance Criteria

### Must Pass

- [ ] File paths are NOT flagged as high-entropy
- [ ] URLs are NOT flagged as high-entropy  
- [ ] SHA hashes are NOT flagged as high-entropy
- [ ] UUIDs are NOT flagged as high-entropy
- [ ] Real secrets (`api_key = sk-proj-xxx`) ARE still flagged
- [ ] Bare high-entropy secrets ARE still flagged
- [ ] All existing tests still pass

### Should Pass

- [ ] Skill descriptions are readable after scanning
- [ ] MCP tool descriptions are preserved
- [ ] Markdown content blocks are not destroyed
- [ ] No performance regression (>10% slowdown)

### Configuration

- [ ] Safe patterns can be disabled via config
- [ ] Proximity check can be disabled via config
- [ ] Proximity window size is configurable

---

## File Changes

```
src/mcp_injector/pii.clj
  - Add safe-pattern? function
  - Add likely-secret-context? function
  - Modify find-all-coordinates entropy detection

test/mcp_injector/pii_test.clj
  - Add comprehensive safe pattern tests
  - Add proximity assignment tests
  - Add real-world false positive tests
  - Expand edge case coverage

dev/specs/pii-redaction.edn
  - Update spec with new approach
```

---

## Example Test Cases (Specific)

### Should NOT Flag (False Positives to Eliminate)

| Input | Pattern Type |
|-------|-------------|
| `/skills/agent-tunnel/` | Unix file path |
| `http://5.78.100.114:19992` | URL with IP |
| `5.78.100.114` | Standalone IP address |
| `a1b2c3d4e5f6789012345678901234567890abcd` | SHA-256 hash |
| `550e8400-e29b-41d4-a716-446655440000` | UUID |
| `mcp__stripe__retrieve_customer` | MCP tool name |
| `skills/ripgrep-search/SKILL.md` | Skill path |

### SHOULD Flag (True Positives to Keep)

| Input | Context | Why |
|-------|---------|-----|
| `sk-proj-a1b2c3D4E5f6G7h8I9j0K1l2M3n4O5p6` | `api_key = sk-proj-xxx` | Assignment |
| `Xy9!Zp2@Qn8#K` | `secret: Xy9!Zp2@Qn8#K` | Assignment |
| `MyS3cr3t!Pass` | `password: MyS3cr3t!Pass` | Assignment |
| `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9` | `token := eyJ...` | Assignment |
| `ant-api-key-v1-ABCDEFG...` | (bare, proximity off) | High entropy |

### Real-World mcp-injector Examples

```clojure
;; FROM YOUR ACTUAL ISSUE:
;; These should NOT be redacted:
"## Remote Tools (MCP)"  ;; OK
"- mcp__stripe: retrieve_customer, list_charges"  ;; OK (2 classes)
"Path: skills/agent-tunnel/"  ;; Should NOT flag path
"URL: http://5.78.100.114:19992"  ;; Should NOT flag URL/IP
"HIGH_ENTROPY_SECRET_e90ee5aca1b2c024bcd85222"  ;; Hash ID, not real secret
"agent-tunnel: Establish, use, and tear down..."  ;; Should NOT flag
```

### Proximity Check Test Cases

```clojure
;; SHOULD flag (has assignment context):
"secret = aB3cD4eF5gH6iJ7kL8"
"api_key: xY9!zW2vU3tS4rQ5p"
"password := MyS3cr3t!"

;; Should NOT flag (no assignment context):
"User passed in aB3cD4eF5gH6iJ7kL8 as argument"
"Response contained xY9!zW2vU3tS4rQ5p from server"
"Found these keys: aB3cD4eF5gH6iJ7kL8, xY9!zW2vU3tS4rQ5p"
```

---

## Questions & Decisions

| Question | Decision |
|----------|----------|
| Phase 2 default? | **ON by default, configurable** |
| IP addresses? | **Always whitelist** |
| Config options? | `:proximity-enabled?`, `:proximity-window`, `:safe-patterns-enabled?` |
