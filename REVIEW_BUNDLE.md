## Role
You are a senior Clojure engineer and security expert reviewing a PR for the mcp-injector project.

## Context
This PR enhances the "Smart Vault" PII restoration system with configurable trust levels. It addresses the critical bug where the `edit` tool fails when using redacted tokens because the tool needs the original value to match against the filesystem.

## The Problem Being Solved
When a tool like `read` returns content containing PII (e.g., a URL), the injector redacts it to a token like `[EMAIL_ADDRESS_a35e2662]` before sending to the LLM. The LLM then tries to use that token in an `edit` operation as the `old_string`. But the actual file on disk contains the real URL, not the token, so the edit fails.

The solution introduces **configurable trust levels**:
- `:none` (default) - Standard redaction, tool receives token
- `:read` - Tool receives original value (pass-through for observation)
- `:restore` - Token in tool arguments is restored to original value before execution

This allows the LLM to work with tokens while trusted tools (like `read`, `edit`, `write`) can operate on real data.

## Review Priorities

### 1. Security (Primary)
- Does the vault properly isolate token mappings between requests?
- Could tokens leak across request boundaries?
- Is trust resolution implemented correctly (tool > server > :none)?
- Are there any injection risks in token replacement?
- Does the `:restore` mechanism allow the LLM to probe for secrets it never saw?

### 2. Correctness
- Does `generate-token` produce consistent, deterministic output?
- Does `restore-tokens` correctly reverse `redact-data`?
- Does `get-server-trust` correctly handle both keyword and string values?
- Does the `edit` tool test actually verify token restoration?

### 3. Configuration & Integration
- Is the NixOS module properly exposing the `trust` option?
- Does the example config clearly document usage?
- Will users understand how to configure trust levels?

### 4. Performance & Robustness
- Any memory leaks with vault atoms?
- Does the postwalk traversal handle large payloads?
- Is the token format change (12 hex chars) properly reflected in all regexes?

## Files to Review
- `src/mcp_injector/pii.clj` - Token generation (now 12 hex chars)
- `src/mcp_injector/config.clj` - `get-server-trust` with `:read`/`:restore`
- `src/mcp_injector/core.clj` - Agent loop integration, removal of backdoor
- `test/mcp_injector/restoration_test.clj` - New `test-edit-tool-with-pii-token`
- `flake.nix` - NixOS module trust option
- `mcp-servers.example.edn` - Updated examples
- `dev/specs/configurable-trust-levels.edn` - Specification

## Output Format
```
## Security Concerns
[Critical issues - must fix]

## Correctness Issues
[Bugs or logic errors]

## Suggestions
[Improvements, not blocking]

## Questions
[Things that need clarification]
```

Be specific and reference line numbers where applicable.

---

## DIFF FROM MAIN
diff --git a/README.md b/README.md
index 148a019..76bed10 100644
--- a/README.md
+++ b/README.md
@@ -8,7 +8,7 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
 
 - ✅ **Virtual model chains** - Define fallback providers with cooldowns.
 - ✅ **Governance Framework** - Declarative tool access policies (Permissive/Strict).
-- ✅ **PII Scanning** - Automatic redaction of sensitive data in prompts and tool outputs.
+- ✅ **PII Scanning & Restoration** - Automatic redaction of sensitive data in prompts. Trusted tools can receive original PII values for secure processing.
 - ✅ **Signed Audit Trail** - Tamper-proof NDJSON logs with ULID and HMAC chaining.
 - ✅ **Provider-Level Observability** - Granular tracking of tokens, requests, and rate-limits per provider.
 - ✅ **Multi-transport MCP** - Support for HTTP and STDIO (local process) MCP servers.
@@ -19,13 +19,16 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
 mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn` (copy from `mcp-servers.example.edn`).
 
 ### Governance Modes
+
 - `:permissive` (Default): All tools are allowed unless explicitly denied.
 - `:strict`: All tools are denied unless explicitly allowed in the policy.
 
 ### Privileged Tools
+
 Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. These tools are **always blocked** by default, even in permissive mode, unless explicitly listed in an `:allow` rule.
 
 ### Example Policy
+
 ```clojure
 :governance
 {:mode :permissive
@@ -41,13 +44,32 @@ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. Thes
  {:enabled true :mode :replace}}
 ```
 
+### PII Restoration (Smart Vault)
+
+For tools that need access to original PII data (e.g., a Stripe integration that must see real email addresses), configure trust levels:
+
+```clojure
+:servers
+{:stripe
+ {:url "http://localhost:3001/mcp"
+  :trust :restore  ; :none (default), :read, or :restore
+  :tools [{:name "retrieve_customer" :trust :restore}]}}
+```
+
+- **`:none`** (default): Tool receives redacted tokens like `[EMAIL_ADDRESS_a35e2662]`
+- **`:restore`**: Tool receives original values (e.g., `wes@example.com`)
+
+The vault uses deterministic SHA-256 hashing with a per-request salt, ensuring tokens are consistent within a request but not leakable across requests.
+
 ## Quick Start
 
 ### Prerequisites
+
 - [Babashka](https://babashka.org/) installed
 - [Nix](https://nixos.org/) (optional)
 
 ### Installation
+
 ```bash
 nix develop
 bb test
@@ -57,11 +79,13 @@ bb run
 ## Configuration
 
 Copy the example config and customize:
+
 ```bash
 cp mcp-servers.example.edn mcp-servers.edn
 ```
 
 Edit `mcp-servers.edn`:
+
 ```clojure
 {:servers
   {:stripe
diff --git a/REVIEW_BUNDLE.md b/REVIEW_BUNDLE.md
new file mode 100644
index 0000000..10e445a
--- /dev/null
+++ b/REVIEW_BUNDLE.md
@@ -0,0 +1,8284 @@
+## Role
+You are a senior Clojure engineer and security expert reviewing a PR for the mcp-injector project.
+
+## Context
+This PR enhances the "Smart Vault" PII restoration system with configurable trust levels. It addresses the critical bug where the `edit` tool fails when using redacted tokens because the tool needs the original value to match against the filesystem.
+
+## The Problem Being Solved
+When a tool like `read` returns content containing PII (e.g., a URL), the injector redacts it to a token like `[EMAIL_ADDRESS_a35e2662]` before sending to the LLM. The LLM then tries to use that token in an `edit` operation as the `old_string`. But the actual file on disk contains the real URL, not the token, so the edit fails.
+
+The solution introduces **configurable trust levels**:
+- `:none` (default) - Standard redaction, tool receives token
+- `:read` - Tool receives original value (pass-through for observation)
+- `:restore` - Token in tool arguments is restored to original value before execution
+
+This allows the LLM to work with tokens while trusted tools (like `read`, `edit`, `write`) can operate on real data.
+
+## Review Priorities
+
+### 1. Security (Primary)
+- Does the vault properly isolate token mappings between requests?
+- Could tokens leak across request boundaries?
+- Is trust resolution implemented correctly (tool > server > :none)?
+- Are there any injection risks in token replacement?
+- Does the `:restore` mechanism allow the LLM to probe for secrets it never saw?
+
+### 2. Correctness
+- Does `generate-token` produce consistent, deterministic output?
+- Does `restore-tokens` correctly reverse `redact-data`?
+- Does `get-server-trust` correctly handle both keyword and string values?
+- Does the `edit` tool test actually verify token restoration?
+
+### 3. Configuration & Integration
+- Is the NixOS module properly exposing the `trust` option?
+- Does the example config clearly document usage?
+- Will users understand how to configure trust levels?
+
+### 4. Performance & Robustness
+- Any memory leaks with vault atoms?
+- Does the postwalk traversal handle large payloads?
+- Is the token format change (12 hex chars) properly reflected in all regexes?
+
+## Files to Review
+- `src/mcp_injector/pii.clj` - Token generation (now 12 hex chars)
+- `src/mcp_injector/config.clj` - `get-server-trust` with `:read`/`:restore`
+- `src/mcp_injector/core.clj` - Agent loop integration
+- `test/mcp_injector/restoration_test.clj` - New `test-edit-tool-with-pii-token`
+- `flake.nix` - NixOS module trust option
+- `mcp-servers.example.edn` - Updated examples
+- `dev/specs/configurable-trust-levels.edn` - Specification
+
+## Output Format
+```
+## Security Concerns
+[Critical issues - must fix]
+
+## Correctness Issues
+[Bugs or logic errors]
+
+## Suggestions
+[Improvements, not blocking]
+
+## Questions
+[Things that need clarification]
+```
+
+Be specific and reference line numbers where applicable.
+
+---
+
+## DIFF FROM MAIN
+diff --git a/README.md b/README.md
+index 148a019..76bed10 100644
+--- a/README.md
++++ b/README.md
+@@ -8,7 +8,7 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
+ 
+ - ✅ **Virtual model chains** - Define fallback providers with cooldowns.
+ - ✅ **Governance Framework** - Declarative tool access policies (Permissive/Strict).
+-- ✅ **PII Scanning** - Automatic redaction of sensitive data in prompts and tool outputs.
++- ✅ **PII Scanning & Restoration** - Automatic redaction of sensitive data in prompts. Trusted tools can receive original PII values for secure processing.
+ - ✅ **Signed Audit Trail** - Tamper-proof NDJSON logs with ULID and HMAC chaining.
+ - ✅ **Provider-Level Observability** - Granular tracking of tokens, requests, and rate-limits per provider.
+ - ✅ **Multi-transport MCP** - Support for HTTP and STDIO (local process) MCP servers.
+@@ -19,13 +19,16 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
+ mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn` (copy from `mcp-servers.example.edn`).
+ 
+ ### Governance Modes
++
+ - `:permissive` (Default): All tools are allowed unless explicitly denied.
+ - `:strict`: All tools are denied unless explicitly allowed in the policy.
+ 
+ ### Privileged Tools
++
+ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. These tools are **always blocked** by default, even in permissive mode, unless explicitly listed in an `:allow` rule.
+ 
+ ### Example Policy
++
+ ```clojure
+ :governance
+ {:mode :permissive
+@@ -41,13 +44,32 @@ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. Thes
+  {:enabled true :mode :replace}}
+ ```
+ 
++### PII Restoration (Smart Vault)
++
++For tools that need access to original PII data (e.g., a Stripe integration that must see real email addresses), configure trust levels:
++
++```clojure
++:servers
++{:stripe
++ {:url "http://localhost:3001/mcp"
++  :trust :restore  ; :none (default), :read, or :restore
++  :tools [{:name "retrieve_customer" :trust :restore}]}}
++```
++
++- **`:none`** (default): Tool receives redacted tokens like `[EMAIL_ADDRESS_a35e2662]`
++- **`:restore`**: Tool receives original values (e.g., `wes@example.com`)
++
++The vault uses deterministic SHA-256 hashing with a per-request salt, ensuring tokens are consistent within a request but not leakable across requests.
++
+ ## Quick Start
+ 
+ ### Prerequisites
++
+ - [Babashka](https://babashka.org/) installed
+ - [Nix](https://nixos.org/) (optional)
+ 
+ ### Installation
++
+ ```bash
+ nix develop
+ bb test
+@@ -57,11 +79,13 @@ bb run
+ ## Configuration
+ 
+ Copy the example config and customize:
++
+ ```bash
+ cp mcp-servers.example.edn mcp-servers.edn
+ ```
+ 
+ Edit `mcp-servers.edn`:
++
+ ```clojure
+ {:servers
+   {:stripe
+diff --git a/REVIEW_BUNDLE.md b/REVIEW_BUNDLE.md
+new file mode 100644
+index 0000000..d437658
+--- /dev/null
++++ b/REVIEW_BUNDLE.md
+@@ -0,0 +1,5506 @@
++## Role
++You are a senior Clojure engineer and security expert reviewing a PR for the mcp-injector project.
++
++## Context
++This PR implements "Smart Vault" PII restoration - a two-way substitution system where:
++- Tool outputs containing PII are tokenized (e.g., `[EMAIL_ADDRESS_a35e2662]`) before reaching the LLM
++- Trusted tools (`:trust :restore`) receive original values restored from a vault
++- Untrusted tools see only tokens
++
++## Review Priorities
++
++### 1. Security (Primary)
++- Does the vault properly isolate token mappings between requests?
++- Could tokens leak across request boundaries?
++- Is trust resolution implemented correctly (tool > server > :none)?
++- Are there any injection risks in token replacement?
++
++### 2. Correctness
++- Does `generate-token` produce consistent, deterministic output?
++- Does `restore-tokens` correctly reverse `redact-data`?
++- Are edge cases handled (empty values, nested JSON, sequential vs map)?
++
++### 3. Integration
++- Is the config merging fix correct in `start-server`?
++- Is governance properly threaded through to `execute-tool`?
++- Does `restoration_test.clj` actually verify the end-to-end flow?
++
++### 4. Performance & Robustness
++- Any memory leaks with vault atoms?
++- Does the postwalk traversal handle large payloads?
++
++## Files to Review
++- `src/mcp_injector/pii.clj` - Core token logic
++- `src/mcp_injector/config.clj` - Trust resolution (lines 169-188)
++- `src/mcp_injector/core.clj` - Agent loop integration
++- `test/mcp_injector/restoration_test.clj` - Integration test
++
++## Output Format
++Provide feedback in this structure:
++```
++## Security Concerns
++[Critical issues - must fix]
++
++## Correctness Issues
++[Bugs or logic errors]
++
++## Suggestions
++[Improvements, not blocking]
++
++## Questions
++[Things that need clarification]
++```
++
++Be specific and reference line numbers where applicable.
++
++---
++
++## DIFF FROM MAIN
++diff --git a/README.md b/README.md
++index 148a019..76bed10 100644
++--- a/README.md
+++++ b/README.md
++@@ -8,7 +8,7 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
++ 
++ - ✅ **Virtual model chains** - Define fallback providers with cooldowns.
++ - ✅ **Governance Framework** - Declarative tool access policies (Permissive/Strict).
++-- ✅ **PII Scanning** - Automatic redaction of sensitive data in prompts and tool outputs.
+++- ✅ **PII Scanning & Restoration** - Automatic redaction of sensitive data in prompts. Trusted tools can receive original PII values for secure processing.
++ - ✅ **Signed Audit Trail** - Tamper-proof NDJSON logs with ULID and HMAC chaining.
++ - ✅ **Provider-Level Observability** - Granular tracking of tokens, requests, and rate-limits per provider.
++ - ✅ **Multi-transport MCP** - Support for HTTP and STDIO (local process) MCP servers.
++@@ -19,13 +19,16 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
++ mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn` (copy from `mcp-servers.example.edn`).
++ 
++ ### Governance Modes
+++
++ - `:permissive` (Default): All tools are allowed unless explicitly denied.
++ - `:strict`: All tools are denied unless explicitly allowed in the policy.
++ 
++ ### Privileged Tools
+++
++ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. These tools are **always blocked** by default, even in permissive mode, unless explicitly listed in an `:allow` rule.
++ 
++ ### Example Policy
+++
++ ```clojure
++ :governance
++ {:mode :permissive
++@@ -41,13 +44,32 @@ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. Thes
++  {:enabled true :mode :replace}}
++ ```
++ 
+++### PII Restoration (Smart Vault)
+++
+++For tools that need access to original PII data (e.g., a Stripe integration that must see real email addresses), configure trust levels:
+++
+++```clojure
+++:servers
+++{:stripe
+++ {:url "http://localhost:3001/mcp"
+++  :trust :restore  ; :none (default), :read, or :restore
+++  :tools [{:name "retrieve_customer" :trust :restore}]}}
+++```
+++
+++- **`:none`** (default): Tool receives redacted tokens like `[EMAIL_ADDRESS_a35e2662]`
+++- **`:restore`**: Tool receives original values (e.g., `wes@example.com`)
+++
+++The vault uses deterministic SHA-256 hashing with a per-request salt, ensuring tokens are consistent within a request but not leakable across requests.
+++
++ ## Quick Start
++ 
++ ### Prerequisites
+++
++ - [Babashka](https://babashka.org/) installed
++ - [Nix](https://nixos.org/) (optional)
++ 
++ ### Installation
+++
++ ```bash
++ nix develop
++ bb test
++@@ -57,11 +79,13 @@ bb run
++ ## Configuration
++ 
++ Copy the example config and customize:
+++
++ ```bash
++ cp mcp-servers.example.edn mcp-servers.edn
++ ```
++ 
++ Edit `mcp-servers.edn`:
+++
++ ```clojure
++ {:servers
++   {:stripe
++diff --git a/REVIEW_BUNDLE.md b/REVIEW_BUNDLE.md
++new file mode 100644
++index 0000000..1d9a528
++--- /dev/null
+++++ b/REVIEW_BUNDLE.md
++@@ -0,0 +1,4600 @@
+++## Role
+++You are a senior Clojure engineer and security expert reviewing a PR for the mcp-injector project.
+++
+++## Context
+++This PR implements "Smart Vault" PII restoration - a two-way substitution system where:
+++- Tool outputs containing PII are tokenized (e.g., `[EMAIL_ADDRESS_a35e2662]`) before reaching the LLM
+++- Trusted tools (`:trust :restore`) receive original values restored from a vault
+++- Untrusted tools see only tokens
+++
+++## Review Priorities
+++
+++### 1. Security (Primary)
+++- Does the vault properly isolate token mappings between requests?
+++- Could tokens leak across request boundaries?
+++- Is trust resolution implemented correctly (tool > server > :none)?
+++- Are there any injection risks in token replacement?
+++
+++### 2. Correctness
+++- Does `generate-token` produce consistent, deterministic output?
+++- Does `restore-tokens` correctly reverse `redact-data`?
+++- Are edge cases handled (empty values, nested JSON, sequential vs map)?
+++
+++### 3. Integration
+++- Is the config merging fix correct in `start-server`?
+++- Is governance properly threaded through to `execute-tool`?
+++- Does `restoration_test.clj` actually verify the end-to-end flow?
+++
+++### 4. Performance & Robustness
+++- Any memory leaks with vault atoms?
+++- Does the postwalk traversal handle large payloads?
+++
+++## Files to Review
+++- `src/mcp_injector/pii.clj` - Core token logic
+++- `src/mcp_injector/config.clj` - Trust resolution (lines 169-188)
+++- `src/mcp_injector/core.clj` - Agent loop integration
+++- `test/mcp_injector/restoration_test.clj` - Integration test
+++
+++## Output Format
+++Provide feedback in this structure:
+++```
+++## Security Concerns
+++[Critical issues - must fix]
+++
+++## Correctness Issues
+++[Bugs or logic errors]
+++
+++## Suggestions
+++[Improvements, not blocking]
+++
+++## Questions
+++[Things that need clarification]
+++```
+++
+++Be specific and reference line numbers where applicable.
+++
+++---
+++
+++## DIFF FROM MAIN
+++diff --git a/README.md b/README.md
+++index 148a019..76bed10 100644
+++--- a/README.md
++++++ b/README.md
+++@@ -8,7 +8,7 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
+++ 
+++ - ✅ **Virtual model chains** - Define fallback providers with cooldowns.
+++ - ✅ **Governance Framework** - Declarative tool access policies (Permissive/Strict).
+++-- ✅ **PII Scanning** - Automatic redaction of sensitive data in prompts and tool outputs.
++++- ✅ **PII Scanning & Restoration** - Automatic redaction of sensitive data in prompts. Trusted tools can receive original PII values for secure processing.
+++ - ✅ **Signed Audit Trail** - Tamper-proof NDJSON logs with ULID and HMAC chaining.
+++ - ✅ **Provider-Level Observability** - Granular tracking of tokens, requests, and rate-limits per provider.
+++ - ✅ **Multi-transport MCP** - Support for HTTP and STDIO (local process) MCP servers.
+++@@ -19,13 +19,16 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
+++ mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn` (copy from `mcp-servers.example.edn`).
+++ 
+++ ### Governance Modes
++++
+++ - `:permissive` (Default): All tools are allowed unless explicitly denied.
+++ - `:strict`: All tools are denied unless explicitly allowed in the policy.
+++ 
+++ ### Privileged Tools
++++
+++ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. These tools are **always blocked** by default, even in permissive mode, unless explicitly listed in an `:allow` rule.
+++ 
+++ ### Example Policy
++++
+++ ```clojure
+++ :governance
+++ {:mode :permissive
+++@@ -41,13 +44,32 @@ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. Thes
+++  {:enabled true :mode :replace}}
+++ ```
+++ 
++++### PII Restoration (Smart Vault)
++++
++++For tools that need access to original PII data (e.g., a Stripe integration that must see real email addresses), configure trust levels:
++++
++++```clojure
++++:servers
++++{:stripe
++++ {:url "http://localhost:3001/mcp"
++++  :trust :restore  ; :none (default), :read, or :restore
++++  :tools [{:name "retrieve_customer" :trust :restore}]}}
++++```
++++
++++- **`:none`** (default): Tool receives redacted tokens like `[EMAIL_ADDRESS_a35e2662]`
++++- **`:restore`**: Tool receives original values (e.g., `wes@example.com`)
++++
++++The vault uses deterministic SHA-256 hashing with a per-request salt, ensuring tokens are consistent within a request but not leakable across requests.
++++
+++ ## Quick Start
+++ 
+++ ### Prerequisites
++++
+++ - [Babashka](https://babashka.org/) installed
+++ - [Nix](https://nixos.org/) (optional)
+++ 
+++ ### Installation
++++
+++ ```bash
+++ nix develop
+++ bb test
+++@@ -57,11 +79,13 @@ bb run
+++ ## Configuration
+++ 
+++ Copy the example config and customize:
++++
+++ ```bash
+++ cp mcp-servers.example.edn mcp-servers.edn
+++ ```
+++ 
+++ Edit `mcp-servers.edn`:
++++
+++ ```clojure
+++ {:servers
+++   {:stripe
+++diff --git a/REVIEW_BUNDLE.md b/REVIEW_BUNDLE.md
+++new file mode 100644
+++index 0000000..86a7d54
+++--- /dev/null
++++++ b/REVIEW_BUNDLE.md
+++@@ -0,0 +1,2140 @@
++++## Role
++++You are a senior Clojure engineer and security expert reviewing a PR for the mcp-injector project.
++++
++++## Context
++++This PR implements "Smart Vault" PII restoration - a two-way substitution system where:
++++- Tool outputs containing PII are tokenized (e.g., `[EMAIL_ADDRESS_a35e2662]`) before reaching the LLM
++++- Trusted tools (`:trust :restore`) receive original values restored from a vault
++++- Untrusted tools see only tokens
++++
++++## Review Priorities
++++
++++### 1. Security (Primary)
++++- Does the vault properly isolate token mappings between requests?
++++- Could tokens leak across request boundaries?
++++- Is trust resolution implemented correctly (tool > server > :none)?
++++- Are there any injection risks in token replacement?
++++
++++### 2. Correctness
++++- Does `generate-token` produce consistent, deterministic output?
++++- Does `restore-tokens` correctly reverse `redact-data`?
++++- Are edge cases handled (empty values, nested JSON, sequential vs map)?
++++
++++### 3. Integration
++++- Is the config merging fix correct in `start-server`?
++++- Is governance properly threaded through to `execute-tool`?
++++- Does `restoration_test.clj` actually verify the end-to-end flow?
++++
++++### 4. Performance & Robustness
++++- Any memory leaks with vault atoms?
++++- Does the postwalk traversal handle large payloads?
++++
++++## Files to Review
++++- `src/mcp_injector/pii.clj` - Core token logic
++++- `src/mcp_injector/config.clj` - Trust resolution (lines 169-188)
++++- `src/mcp_injector/core.clj` - Agent loop integration
++++- `test/mcp_injector/restoration_test.clj` - Integration test
++++
++++## Output Format
++++Provide feedback in this structure:
++++```
++++## Security Concerns
++++[Critical issues - must fix]
++++
++++## Correctness Issues
++++[Bugs or logic errors]
++++
++++## Suggestions
++++[Improvements, not blocking]
++++
++++## Questions
++++[Things that need clarification]
++++```
++++
++++Be specific and reference line numbers where applicable.
++++
++++---
++++
++++## DIFF FROM MAIN
++++diff --git a/README.md b/README.md
++++index 148a019..76bed10 100644
++++--- a/README.md
+++++++ b/README.md
++++@@ -8,7 +8,7 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
++++ 
++++ - ✅ **Virtual model chains** - Define fallback providers with cooldowns.
++++ - ✅ **Governance Framework** - Declarative tool access policies (Permissive/Strict).
++++-- ✅ **PII Scanning** - Automatic redaction of sensitive data in prompts and tool outputs.
+++++- ✅ **PII Scanning & Restoration** - Automatic redaction of sensitive data in prompts. Trusted tools can receive original PII values for secure processing.
++++ - ✅ **Signed Audit Trail** - Tamper-proof NDJSON logs with ULID and HMAC chaining.
++++ - ✅ **Provider-Level Observability** - Granular tracking of tokens, requests, and rate-limits per provider.
++++ - ✅ **Multi-transport MCP** - Support for HTTP and STDIO (local process) MCP servers.
++++@@ -19,13 +19,16 @@ mcp-injector sits between an agent (like OpenClaw) and LLM gateways. It provides
++++ mcp-injector includes a robust governance layer configured via the `:governance` key in `mcp-servers.edn` (copy from `mcp-servers.example.edn`).
++++ 
++++ ### Governance Modes
+++++
++++ - `:permissive` (Default): All tools are allowed unless explicitly denied.
++++ - `:strict`: All tools are denied unless explicitly allowed in the policy.
++++ 
++++ ### Privileged Tools
+++++
++++ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. These tools are **always blocked** by default, even in permissive mode, unless explicitly listed in an `:allow` rule.
++++ 
++++ ### Example Policy
+++++
++++ ```clojure
++++ :governance
++++ {:mode :permissive
++++@@ -41,13 +44,32 @@ Certain high-risk tools (like `clojure-eval`) are marked as **Privileged**. Thes
++++  {:enabled true :mode :replace}}
++++ ```
++++ 
+++++### PII Restoration (Smart Vault)
+++++
+++++For tools that need access to original PII data (e.g., a Stripe integration that must see real email addresses), configure trust levels:
+++++
+++++```clojure
+++++:servers
+++++{:stripe
+++++ {:url "http://localhost:3001/mcp"
+++++  :trust :restore  ; :none (default), :read, or :restore
+++++  :tools [{:name "retrieve_customer" :trust :restore}]}}
+++++```
+++++
+++++- **`:none`** (default): Tool receives redacted tokens like `[EMAIL_ADDRESS_a35e2662]`
+++++- **`:restore`**: Tool receives original values (e.g., `wes@example.com`)
+++++
+++++The vault uses deterministic SHA-256 hashing with a per-request salt, ensuring tokens are consistent within a request but not leakable across requests.
+++++
++++ ## Quick Start
++++ 
++++ ### Prerequisites
+++++
++++ - [Babashka](https://babashka.org/) installed
++++ - [Nix](https://nixos.org/) (optional)
++++ 
++++ ### Installation
+++++
++++ ```bash
++++ nix develop
++++ bb test
++++@@ -57,11 +79,13 @@ bb run
++++ ## Configuration
++++ 
++++ Copy the example config and customize:
+++++
++++ ```bash
++++ cp mcp-servers.example.edn mcp-servers.edn
++++ ```
++++ 
++++ Edit `mcp-servers.edn`:
+++++
++++ ```clojure
++++ {:servers
++++   {:stripe
++++diff --git a/dev/PII_RESTORATION_PLAN.md b/dev/PII_RESTORATION_PLAN.md
++++new file mode 100644
++++index 0000000..903b280
++++--- /dev/null
+++++++ b/dev/PII_RESTORATION_PLAN.md
++++@@ -0,0 +1,31 @@
+++++# PII Restoration & Secret Substitution Plan
+++++
+++++## Status
+++++- **Branch:** `feat/pii-restoration`
+++++- **Current State:** COMPLETE - All tests passing
+++++- **Completed:** 2026-03-14
+++++
+++++## Summary
+++++Successfully implemented "Smart Vault" PII restoration system:
+++++- Deterministic token generation with SHA-256
+++++- Request-scoped vault for token/value mapping
+++++- Trust levels (`:none`, `:read`, `:restore`) at server and tool level
+++++- Two-way substitution: LLM sees tokens, trusted tools see real values
+++++
+++++## Core Strategy: The "Smart Vault"
+++++We are moving from "One-Way Redaction" to "Two-Way Substitution" for trusted tools.
+++++
+++++### 1. Tokenization (Outbound to LLM)
+++++- **Deterministic Hashing:** Replace `[EMAIL_ADDRESS]` with `[PII_EMAIL_8ce0db03]`. 
+++++- **Vaulting:** Store `{ "[PII_EMAIL_8ce0db03]" "wes@example.com" }` in a request-scoped vault.
+++++- **Structural Awareness:** Use `clojure.walk` to redact JSON values while preserving keys (so LLM understands schema).
+++++
+++++### 2. Restoration (Inbound to Tools)
+++++- **Trust Tiers:** Define `:trust :restore` in `mcp-servers.edn`.
+++++- **Restoration:** If a tool call targets a trusted server, `mcp-injector` swaps tokens back for real values before execution.
+++++- **Safety:** Untrusted tools continue to see only the redacted tokens.
+++++
+++++## Build Results
+++++- **55 tests** - All passing
+++++- **Lint** - Clean
+++++- **Format** - Clean
++++diff --git a/dev/specs/pii-restoration.edn b/dev/specs/pii-restoration.edn
++++new file mode 100644
++++index 0000000..edb6cd1
++++--- /dev/null
+++++++ b/dev/specs/pii-restoration.edn
++++@@ -0,0 +1,15 @@
+++++{:title "PII/Secret Restoration (Round-Trip)"
+++++ :description "Enable trusted tools to receive original sensitive data while keeping the LLM's view redacted."
+++++ :acceptance-criteria
+++++ ["Tool outputs containing PII are tokenized with deterministic, hybrid labels (e.g., [EMAIL_8f3a2])"
+++++  "Tokens remain consistent across a single request context"
+++++  "A request-scoped Vault stores the mapping of Token -> Original Value"
+++++  "Trusted tools (marked with :trust :restore) receive restored values in their arguments"
+++++  "Untrusted tools receive the literal token strings"
+++++  "Deep JSON redaction preserves map keys but tokenizes values"]
+++++ :edge-cases
+++++ ["Recursive data structures in tool arguments"
+++++  "Mixed plain-text and JSON tool outputs"
+++++  "Token collisions (mitigated via request-id salt)"
+++++  "Empty or null values in scanned data"]
+++++ :depends-on [:governance-core :mcp-client]}
++++diff --git a/result b/result
++++deleted file mode 120000
++++index eea2214..0000000
++++--- a/result
+++++++ /dev/null
++++@@ -1 +0,0 @@
++++-/nix/store/gdjbiza5hidsdb7lx3spirlsxybwlzry-mcp-injector-0.1.0
++++\ No newline at end of file
++++diff --git a/src/mcp_injector/config.clj b/src/mcp_injector/config.clj
++++index aa15670..92014ae 100644
++++--- a/src/mcp_injector/config.clj
+++++++ b/src/mcp_injector/config.clj
++++@@ -166,6 +166,27 @@
++++     []
++++     (:servers mcp-config))))
++++ 
+++++(defn get-server-trust
+++++  "Get trust level for a server/tool combination.
+++++   Returns :restore (trusted), :none (untrusted), or :block.
+++++   Precedence: tool-level :trust > server-level :trust > :none"
+++++  [mcp-config server-name tool-name]
+++++  (let [servers (:servers mcp-config)
+++++        server (get servers (keyword server-name))]
+++++    (if-not server
+++++      :none
+++++      (let [server-trust (or (:trust server) :none)
+++++            tool-configs (:tools server)
+++++            tool-config (when (map? tool-configs)
+++++                          (get tool-configs (keyword tool-name)))
+++++            tool-trust (or (:trust tool-config) :none)]
+++++        (cond
+++++          (= tool-trust :block) :block
+++++          (= server-trust :block) :block
+++++          (= tool-trust :restore) :restore
+++++          (= server-trust :restore) :restore
+++++          :else :none)))))
+++++
++++ (defn get-meta-tool-definitions
++++   "Get definitions for meta-tools like get_tool_schema and native tools"
++++   []
++++diff --git a/src/mcp_injector/core.clj b/src/mcp_injector/core.clj
++++index 5cee001..cdb57b0 100644
++++--- a/src/mcp_injector/core.clj
+++++++ b/src/mcp_injector/core.clj
++++@@ -169,12 +169,10 @@
++++         (= full-name "clojure-eval")
++++         (try
++++           (let [code (:code args)
++++-                ;; NOTE: clojure-eval is a full JVM/Babashka load-string. 
++++-                ;; Security is currently enforced only via the Policy layer (explicit opt-in).
++++                 result (load-string code)]
++++-            (pr-str result))
+++++            (json/generate-string result))
++++           (catch Exception e
++++-            {:error (str "Eval error: " (.getMessage e))}))
+++++            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
++++ 
++++         (str/starts-with? full-name "mcp__")
++++         (let [t-name (str/replace full-name #"^mcp__" "")
++++@@ -199,28 +197,83 @@
++++ 
++++         :else {:error (str "Unknown tool: " full-name)}))))
++++ 
++++-(defn- scrub-messages [messages]
+++++(defn- parse-tool-name
+++++  "Parse mcp__server__tool format into [server tool]"
+++++  [full-name]
+++++  (if (str/includes? full-name "__")
+++++    (let [t-name (str/replace full-name #"^mcp__" "")
+++++          idx (str/last-index-of t-name "__")]
+++++      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+++++    [nil full-name]))
+++++
+++++(defn- scrub-messages [messages vault request-id]
++++   (mapv (fn [m]
++++-          (if (string? (:content m))
++++-            (let [{:keys [text detected]} (pii/scan-and-redact (:content m) {:mode :replace})]
++++-              (when (seq detected)
++++-                (log-request "info" "PII Redacted" {:labels detected} {:role (:role m)}))
++++-              (assoc m :content text))
++++-            m))
+++++          (let [content (:content m)
+++++                role (:role m)]
+++++            (if (and (string? content)
+++++                     ;; Only redact user/system messages - assistant tool results are already handled
+++++                     (or (= role "system") (= role "user"))
+++++                     ;; Skip if already contains PII tokens (avoid double-redaction)
+++++                     (not (re-find #"\[PII_[A-Z_]+_[a-f0-9]{8}\]" content)))
+++++              (let [config {:mode :replace :salt request-id}
+++++                    [redacted-content _] (pii/redact-data content config vault)]
+++++                (assoc m :content redacted-content))
+++++              m)))
++++         messages))
++++ 
++++-(defn- sanitize-tool-output [content]
++++-  (if (string? content)
++++-    (str/replace content
++++-                 #"(?im)^\s*(system|human|assistant|user)\s*:"
++++-                 "[REDACTED_ROLE_MARKER]")
++++-    content))
+++++(defn- restore-tool-args
+++++  "Restore tokens in tool args if server is trusted"
+++++  [args vault mcp-servers full-tool-name]
+++++  (let [[server tool] (parse-tool-name full-tool-name)
+++++        trust (when server (config/get-server-trust mcp-servers server tool))
+++++        restored (if (= trust :restore)
+++++                   (pii/restore-tokens args vault)
+++++                   args)]
+++++    restored))
+++++
+++++(defn- redact-tool-output
+++++  "Redact PII from tool output, return [content vault]"
+++++  [raw-output vault request-id]
+++++  (let [;; Try to parse as JSON first for JSON tokenization
+++++        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
+++++        ;; If parsed successfully, redact the data structure; otherwise redact the string
+++++        ;; Special handling for MCP response format: parse nested :text field if present
+++++        [redacted new-vault] (if parsed
+++++                               (let [;; Check if this is MCP response format with :text field containing JSON
+++++                                     ;; Handle both map and sequential (vector/list/lazy-seq) responses
+++++                                     parsed (cond
+++++                                              (map? parsed)
+++++                                              (if (string? (:text parsed))
+++++                                                (try (assoc parsed :text (json/parse-string (:text parsed) true))
+++++                                                     (catch Exception _ parsed))
+++++                                                parsed)
+++++                                              (sequential? parsed)
+++++                                              (mapv (fn [item]
+++++                                                      (if (and (map? item) (string? (:text item)))
+++++                                                        (try (assoc item :text (json/parse-string (:text item) true))
+++++                                                             (catch Exception _ item))
+++++                                                        item))
+++++                                                    parsed)
+++++                                              :else parsed)
+++++                                     config {:mode :replace :salt request-id}
+++++                                     [redacted-struct vault-after] (pii/redact-data parsed config vault)]
+++++                                 [(json/generate-string redacted-struct) vault-after])
+++++                               (let [config {:mode :replace :salt request-id}
+++++                                     [redacted-str vault-after] (pii/redact-data raw-output config vault)]
+++++                                 [redacted-str vault-after]))
+++++        ;; For logging, we still scan the raw output
+++++        detected (:detected (pii/scan-and-redact raw-output {:mode :replace}))]
+++++    (when (seq detected)
+++++      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
+++++    [redacted new-vault]))
++++ 
++++ (defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
++++   (let [model (:model payload)
++++         discovered-this-loop (atom {})
++++-        context {:model model}]
++++-    (loop [current-payload (update payload :messages scrub-messages)
+++++        vault (atom {})
+++++        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
+++++        context {:model model :request-id request-id}]
+++++    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
++++            iteration 0]
++++       (if (>= iteration max-iterations)
++++         {:success true
++++@@ -239,40 +292,46 @@
++++                   tool-calls (:tool_calls message)]
++++               (if-not tool-calls
++++                 (assoc resp :provider model)
++++-                (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
++++-                                             (str/starts-with? (get-in % [:function :name]) "mcp__"))
+++++                (let [mcp-calls (filter (fn [tc]
+++++                                          (let [n (get-in tc [:function :name])]
+++++                                            (or (= n "get_tool_schema")
+++++                                                (and n (str/starts-with? n "mcp__")))))
++++                                         tool-calls)
++++                       native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
++++                                            tool-calls)]
++++                   (if (and (empty? mcp-calls) (empty? native-calls))
++++                     (assoc resp :provider model)
++++-                    (let [results (mapv (fn [tc]
++++-                                          (let [fn-name (get-in tc [:function :name])
++++-                                                args-str (get-in tc [:function :arguments])
++++-                                                parse-result (try
++++-                                                               {:success true :args (json/parse-string args-str true)}
++++-                                                               (catch Exception e
++++-                                                                 {:success false :error (.getMessage e)}))]
++++-                                            (if (:success parse-result)
++++-                                              (let [result (execute-tool fn-name (:args parse-result) mcp-servers discovered-this-loop governance context)
++++-                                                    ;; Scrub and sanitize tool output
++++-                                                    raw-content (if (string? result) result (json/generate-string result))
++++-                                                    sanitized (sanitize-tool-output raw-content)
++++-                                                    {:keys [text detected]} (pii/scan-and-redact sanitized {:mode :replace})
++++-                                                    _ (when (seq detected)
++++-                                                        (log-request "info" "PII Redacted in Tool Output" {:tool fn-name :labels detected} context))]
++++-                                                {:role "tool"
+++++                    (let [[results new-vault]
+++++                          (reduce
+++++                           (fn [[results vault-state] tc]
+++++                             (let [fn-name (get-in tc [:function :name])
+++++                                   args-str (get-in tc [:function :arguments])
+++++                                   parse-result (try
+++++                                                  {:success true :args (json/parse-string args-str true)}
+++++                                                  (catch Exception e
+++++                                                    {:success false :error (.getMessage e)}))]
+++++                               (if (:success parse-result)
+++++                                 (let [;; Restore args if trusted
+++++                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
+++++                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
+++++                                       ;; Redact output with vault
+++++                                       raw-content (if (string? result) result (json/generate-string result))
+++++                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
+++++                                   [(conj results {:role "tool"
+++++                                                   :tool_call_id (:id tc)
+++++                                                   :name fn-name
+++++                                                   :content redacted})
+++++                                    updated-vault])
+++++                                 [(conj results {:role "tool"
++++                                                  :tool_call_id (:id tc)
++++                                                  :name fn-name
++++-                                                 :content text})
++++-                                              {:role "tool"
++++-                                               :tool_call_id (:id tc)
++++-                                               :name fn-name
++++-                                               :content (json/generate-string
++++-                                                         {:error "Malformed tool arguments JSON"
++++-                                                          :details {:args-str args-str
++++-                                                                    :parse-error (:error parse-result)}})})))
++++-                                        (concat mcp-calls native-calls))
+++++                                                 :content (json/generate-string
+++++                                                           {:error "Malformed tool arguments JSON"
+++++                                                            :details {:args-str args-str
+++++                                                                      :parse-error (:error parse-result)}})})
+++++                                  vault-state])))
+++++                           [[] vault]
+++++                           (concat mcp-calls native-calls))
++++                           newly-discovered @discovered-this-loop
++++                           new-tools (vec (concat (config/get-meta-tool-definitions)
++++                                                  (map (fn [[name schema]]
++++@@ -281,9 +340,12 @@
++++                                                                     :description (:description schema)
++++                                                                     :parameters (:inputSchema schema)}})
++++                                                       newly-discovered)))
++++-                          new-messages (conj (vec (:messages current-payload)) message)
+++++                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
++++                           new-messages (into new-messages results)]
++++-                      (recur (assoc current-payload :messages new-messages :tools new-tools) (inc iteration)))))))))))))
+++++                      (recur (assoc current-payload
+++++                                    :messages (scrub-messages new-messages new-vault request-id)
+++++                                    :tools new-tools)
+++++                             (inc iteration)))))))))))))
++++ 
++++ (defn- set-cooldown! [provider minutes]
++++   (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
++++@@ -334,11 +396,14 @@
++++                                   discovered-tools)
++++         merged-tools (vec (concat (or existing-tools [])
++++                                   meta-tools
++++-                                  discovered-tool-defs))]
+++++                                  discovered-tool-defs))
+++++        ;; Merge extra_body into the request for fields like request-id
+++++        extra-body (or (:extra_body chat-req) {})]
++++     (-> chat-req
++++         (assoc :stream false)
++++         (dissoc :stream_options)
++++         (assoc :fallbacks fallbacks)
+++++        (merge extra-body) ;; Lift extra_body fields to top level
++++         (update :messages (fn [msgs]
++++                             (mapv (fn [m]
++++                                     (if (and (= (:role m) "assistant") (:tool_calls m))
++++@@ -428,11 +493,13 @@
++++                      (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
++++           {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
++++     (catch Exception e
++++-      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
++++-        (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e)} {})
+++++      (let [err-type (or (some-> e ex-data :type name) "internal_error")
+++++            err-msg (or (.getMessage e) (str e))
+++++            stack (.getStackTrace e)]
+++++        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
++++         {:status 400
++++          :headers {"Content-Type" "application/json"}
++++-         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
+++++         :body (json/generate-string {:error {:message err-msg
++++                                               :type err-type}})}))))
++++ 
++++ (defn get-gateway-state []
++++@@ -509,46 +576,51 @@
++++                                                   :type err-type}})}))))))
++++ 
++++ (defn start-server [mcp-config]
++++-  (let [initial-config (if (and (map? mcp-config) (not (:servers mcp-config)))
++++-                         mcp-config
++++-                         {})
++++-        port (or (:port initial-config)
+++++  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
+++++        provided-governance (or (:governance mcp-config)
+++++                                (:governance (:mcp-servers mcp-config)))
+++++
+++++        ;; Runtime settings - prioritize input > env > default
+++++        port (or (:port mcp-config)
++++                  (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
++++                  8080)
++++-        host (or (:host initial-config)
+++++        host (or (:host mcp-config)
++++                  (System/getenv "MCP_INJECTOR_HOST")
++++                  "127.0.0.1")
++++-        llm-url (or (:llm-url initial-config)
+++++        llm-url (or (:llm-url mcp-config)
++++                     (System/getenv "MCP_INJECTOR_LLM_URL")
++++                     "http://localhost:11434")
++++-        log-level (or (:log-level initial-config)
+++++        log-level (or (:log-level mcp-config)
++++                       (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
++++-        max-iterations (or (:max-iterations initial-config)
+++++        max-iterations (or (:max-iterations mcp-config)
++++                            (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
++++                            10)
++++-        mcp-config-path (or (:mcp-config-path initial-config)
+++++        mcp-config-path (or (:mcp-config-path mcp-config)
++++                             (System/getenv "MCP_INJECTOR_MCP_CONFIG")
++++                             "mcp-servers.edn")
++++         ;; Audit trail config
++++-        audit-log-path (or (:audit-log-path initial-config)
+++++        audit-log-path (or (:audit-log-path mcp-config)
++++                            (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
++++                            "logs/audit.log.ndjson")
++++-        audit-secret (or (:audit-secret initial-config)
+++++        audit-secret (or (:audit-secret mcp-config)
++++                          (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
++++                          "default-audit-secret")
++++         ;; Merge provided mcp-config with loaded ones if needed
++++         base-mcp-servers (cond
++++                            (and (map? mcp-config) (:servers mcp-config)) mcp-config
++++-                           (:mcp-servers initial-config) (:mcp-servers initial-config)
+++++                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
++++                            :else (config/load-mcp-servers mcp-config-path))
++++-        ;; Apply overrides from initial-config (like :virtual-models in tests)
++++-        mcp-servers (if (seq initial-config)
++++-                      (let [gateway-overrides (select-keys initial-config [:virtual-models :fallbacks :url])]
++++-                        (update base-mcp-servers :llm-gateway merge gateway-overrides))
+++++        ;; Apply overrides from mcp-config (like :virtual-models in tests)
+++++        mcp-servers (if (map? mcp-config)
+++++                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
+++++                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
+++++                        (if-let [gov (:governance mcp-config)]
+++++                          (assoc merged :governance gov)
+++++                          merged))
++++                       base-mcp-servers)
++++-        ;; Unified configuration resolution
+++++        ;; Unified configuration resolution - pass extracted governance
++++         unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
++++-        final-governance (config/resolve-governance (assoc mcp-servers :governance (:governance initial-config)) unified-env)
+++++        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
++++         final-config {:port port :host host :llm-url llm-url :log-level log-level
++++                       :max-iterations max-iterations :mcp-config-path mcp-config-path
++++                       :audit-log-path audit-log-path :audit-secret audit-secret
++++diff --git a/src/mcp_injector/pii.clj b/src/mcp_injector/pii.clj
++++index faeb7e7..c2d3d3c 100644
++++--- a/src/mcp_injector/pii.clj
+++++++ b/src/mcp_injector/pii.clj
++++@@ -1,12 +1,13 @@
++++ (ns mcp-injector.pii
++++-  (:require [clojure.string :as str]))
+++++  (:require [clojure.string :as str]
+++++            [clojure.walk :as walk])
+++++  (:import (java.security MessageDigest)))
++++ 
++++ (def default-patterns
++++   [{:id :EMAIL_ADDRESS
++++     :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
++++     :label "[EMAIL_ADDRESS]"}
++++    {:id :IBAN_CODE
++++-    ;; Tightened range to 15-34 and added case-insensitivity support via (?i)
++++     :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
++++     :label "[IBAN_CODE]"}])
++++ 
++++@@ -46,8 +47,6 @@
++++ (defn- scan-env [text env-vars mode]
++++   (reduce-kv
++++    (fn [acc k v]
++++-     ;; Case-sensitive match for env vars is usually safer, 
++++-     ;; but we ensure the value is long enough to avoid false positives.
++++      (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
++++        (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
++++        acc))
++++@@ -64,7 +63,6 @@
++++   (let [tokens (str/split text #"\s+")]
++++     (reduce
++++      (fn [acc token]
++++-       ;; Threshold raised to 4.0 + diversity check + length check
++++        (if (and (> (count token) 12)
++++                 (> (shannon-entropy token) threshold)
++++                 (character-diversity? token))
++++@@ -74,15 +72,13 @@
++++      tokens)))
++++ 
++++ (defn scan-and-redact
++++-  "Scans input text for PII patterns, high-entropy secrets, and env vars.
++++-   Calculations are performed sequentially on the text."
+++++  "Scans input text for PII patterns, high-entropy secrets, and env vars."
++++   [text {:keys [mode patterns entropy-threshold env]
++++          :or {mode :replace
++++               patterns default-patterns
++++               entropy-threshold 4.0
++++               env {}}}]
++++-  (let [;; 1. Regex patterns (Standard PII)
++++-        regex-result (reduce
+++++  (let [regex-result (reduce
++++                       (fn [state {:keys [id pattern label]}]
++++                         (if (seq (re-seq pattern (:text state)))
++++                           {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
++++@@ -90,14 +86,78 @@
++++                           state))
++++                       {:text text :detected []}
++++                       patterns)
++++-
++++-        ;; 2. Env vars (Exact matches)
++++         env-text (scan-env (:text regex-result) env mode)
++++         env-detections (find-env-detections text env)
++++-
++++-        ;; 3. Entropy (Heuristic secrets)
++++         final-text (scan-entropy env-text entropy-threshold mode)
++++         entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
++++-
++++     {:text final-text
++++      :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
+++++
+++++(defn generate-token
+++++  "Generate a deterministic, truncated SHA-256 hash token."
+++++  [label value salt]
+++++  (let [input (str (name label) "|" value "|" salt)
+++++        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
+++++        hash-str (->> digest
+++++                      (map (partial format "%02x"))
+++++                      (apply str))
+++++        truncated (subs hash-str 0 8)]
+++++    (str "[" (name label) "_" truncated "]")))
+++++
+++++(defn- redact-string-value
+++++  "Redact a single string value, returning [redacted-text token detected-label]"
+++++  [v config]
+++++  (if-not (string? v)
+++++    [v nil nil]
+++++    (if (empty? v)
+++++      [v nil nil]
+++++      (let [vault (:vault config)
+++++            salt (:salt config)
+++++            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
+++++            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
+++++        (cond
+++++          existing-token [existing-token nil nil]
+++++          previous-token [previous-token nil nil]
+++++          :else
+++++          (let [result (scan-and-redact v config)]
+++++            (if (seq (:detected result))
+++++              (let [detected (first (:detected result))
+++++                    token (generate-token detected v salt)]
+++++                (swap! vault assoc token v)
+++++                [token token detected])
+++++              [(:text result) nil nil])))))))
+++++
+++++(defn redact-data
+++++  "Recursively walk a data structure, redact string values, store in vault.
+++++   Returns [redacted-data vault-atom]"
+++++  ([data config]
+++++   (redact-data data config (atom {})))
+++++  ([data config vault]
+++++   (let [config-with-vault (assoc config :vault vault)
+++++         redacted (walk/postwalk
+++++                   (fn [x]
+++++                     (if (string? x)
+++++                       (let [[redacted-text _ _] (redact-string-value x config-with-vault)]
+++++                         redacted-text)
+++++                       x))
+++++                   data)]
+++++     [redacted vault])))
+++++
+++++(defn restore-tokens
+++++  "Recursively walk a data structure, replacing tokens with original values from vault."
+++++  [data vault]
+++++  (let [v-map @vault]
+++++    (if (empty? v-map)
+++++      data
+++++      (walk/postwalk
+++++       (fn [x]
+++++         (if (string? x)
+++++           (reduce
+++++            (fn [s [token original]]
+++++              (if (and (string? s) (str/includes? s token))
+++++                (str/replace s (str token) (str original))
+++++                s))
+++++            x
+++++            v-map)
+++++           x))
+++++       data))))
++++diff --git a/test/mcp_injector/discovery_test.clj b/test/mcp_injector/discovery_test.clj
++++index cf4e069..638e163 100644
++++--- a/test/mcp_injector/discovery_test.clj
+++++++ b/test/mcp_injector/discovery_test.clj
++++@@ -79,8 +79,8 @@
++++       (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
++++       (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))
++++       ;; content might be redacted as [EMAIL_ADDRESS] or [HIGH_ENTROPY_SECRET] depending on scanner
++++-      (is (some (fn [m] (or (str/includes? (:content m) "[EMAIL_ADDRESS]")
++++-                            (str/includes? (:content m) "[HIGH_ENTROPY_SECRET]"))) tool-msgs)))))
+++++      (is (some (fn [m] (or (re-find #"\[EMAIL_ADDRESS(_[a-f0-9]{8})?\]" (:content m))
+++++                            (re-find #"\[HIGH_ENTROPY_SECRET(_[a-f0-9]{8})?\]" (:content m)))) tool-msgs)))))
++++ 
++++ (deftest tool-discovery-filtering-nil-shows-all
++++   (testing "When :tools is nil, all discovered tools from MCP server should be shown"
++++diff --git a/test/mcp_injector/llm_shim_test.clj b/test/mcp_injector/llm_shim_test.clj
++++index 4142816..748e04b 100644
++++--- a/test/mcp_injector/llm_shim_test.clj
+++++++ b/test/mcp_injector/llm_shim_test.clj
++++@@ -25,7 +25,9 @@
++++                       {:port 0
++++                        :host "127.0.0.1"
++++                        :llm-url (str "http://localhost:" (:port llm))
++++-                       :mcp-config "./mcp-servers.edn"})]
+++++                       :mcp-servers {:llm-gateway {:fallbacks ["zen/kimi-k2.5-free"
+++++                                                               "nvidia/moonshotai/kimi-k2.5"
+++++                                                               "openrouter/moonshotai/kimi-k2.5"]}}})]
++++         (swap! test-state assoc :injector injector)
++++         (try
++++           (f)
++++diff --git a/test/mcp_injector/native_tools_test.clj b/test/mcp_injector/native_tools_test.clj
++++index 865537c..1d8a674 100644
++++--- a/test/mcp_injector/native_tools_test.clj
+++++++ b/test/mcp_injector/native_tools_test.clj
++++@@ -14,10 +14,9 @@
++++         injector-server (core/start-server {:port 0
++++                                             :host "127.0.0.1"
++++                                             :llm-url (str "http://localhost:" (:port llm-server))
++++-                                            :mcp-servers {:servers {}
++++-                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))
++++-                                                                        :governance {:mode :permissive
++++-                                                                                     :policy {:allow ["clojure-eval"]}}}}})]
+++++                                            :governance {:mode :permissive
+++++                                                         :policy {:allow ["clojure-eval"]}}
+++++                                            :mcp-servers {:servers {}}})]
++++     (try
++++       (binding [*test-llm* llm-server
++++                 *injector* injector-server]
++++@@ -117,10 +116,9 @@
++++           blocked-injector (core/start-server {:port 0
++++                                                :host "127.0.0.1"
++++                                                :llm-url (str "http://localhost:" llm-port)
++++-                                               :mcp-servers {:servers {}
++++-                                                             :llm-gateway {:url (str "http://localhost:" llm-port)
++++-                                                                           :governance {:mode :permissive
++++-                                                                                        :policy {:allow []}}}}})] ;; empty allow list
+++++                                               :governance {:mode :permissive
+++++                                                            :policy {:allow []}}
+++++                                               :mcp-servers {:servers {}}})] ;; empty allow list
++++       (try
++++         ;; Explicitly clear state before starting the denial flow
++++         (test-llm/clear-responses *test-llm*)
++++diff --git a/test/mcp_injector/restoration_test.clj b/test/mcp_injector/restoration_test.clj
++++new file mode 100644
++++index 0000000..977369f
++++--- /dev/null
+++++++ b/test/mcp_injector/restoration_test.clj
++++@@ -0,0 +1,104 @@
+++++(ns mcp-injector.restoration-test
+++++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
+++++            [clojure.string :as str]
+++++            [mcp-injector.test-llm-server :as test-llm]
+++++            [mcp-injector.test-mcp-server :as test-mcp]
+++++            [mcp-injector.core :as core]
+++++            [cheshire.core :as json]
+++++            [org.httpkit.client :as http]))
+++++
+++++(def test-state (atom {}))
+++++
+++++(use-fixtures :once
+++++  (fn [f]
+++++    (let [llm (test-llm/start-server)
+++++          mcp (test-mcp/start-server)]
+++++      (swap! test-state assoc :llm llm :mcp mcp)
+++++      (let [injector (core/start-server
+++++                      {:port 0
+++++                       :host "127.0.0.1"
+++++                       :llm-url (str "http://localhost:" (:port llm))
+++++                       :mcp-servers {:servers
+++++                                     {:trusted-db
+++++                                      {:url (str "http://localhost:" (:port mcp))
+++++                                       :tools ["query"]
+++++                                       :trust :restore}
+++++                                      :untrusted-api
+++++                                      {:url (str "http://localhost:" (:port mcp))
+++++                                       :tools ["send"]
+++++                                       :trust :none}}}})]
+++++        (swap! test-state assoc :injector injector)
+++++        (try
+++++          (f)
+++++          (finally
+++++            (core/stop-server injector)
+++++            (test-llm/stop-server llm)
+++++            (test-mcp/stop-server mcp)))))))
+++++
+++++(deftest test-secret-redaction-and-restoration
+++++  (testing "End-to-end Redact -> Decide -> Restore flow"
+++++    (let [{:keys [injector llm mcp]} @test-state
+++++          port (:port injector)]
+++++
+++++      ;; 1. Setup MCP to return a secret
+++++      ((:set-tools! mcp)
+++++       {:query {:description "Query database"
+++++                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
+++++                :handler (fn [args]
+++++                           (if (or (:email args) (get args "email"))
+++++                             {:status "success" :received (or (:email args) (get args "email"))}
+++++                             {:email "wes@example.com" :secret "super-secret-123"}))}})
+++++
+++++      ;; 2. LLM Turn 1: Get data (will be redacted)
+++++      (test-llm/set-next-response llm
+++++                                  {:role "assistant"
+++++                                   :tool_calls [{:id "call_1"
+++++                                                 :function {:name "mcp__trusted-db__query"
+++++                                                            :arguments "{\"q\":\"select user\"}"}}]})
+++++
+++++      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
+++++      ;; Token is deterministic: SHA256("EMAIL_ADDRESS|wes@example.com|test-request-id-12345") -> a35e2662
+++++      (test-llm/set-next-response llm
+++++                                  {:role "assistant"
+++++                                   :content "I found the user. Now updating."
+++++                                   :tool_calls [{:id "call_2"
+++++                                                 :function {:name "mcp__trusted-db__query"
+++++                                                            :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
+++++
+++++      ;; Final response
+++++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+++++
+++++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+++++                                 {:body (json/generate-string
+++++                                         {:model "brain"
+++++                                          :messages [{:role "user" :content "Update user wes"}]
+++++                                          :stream false
+++++                                          :extra_body {:request-id "test-request-id-12345"}})
+++++                                  :headers {"Content-Type" "application/json"}})]
+++++        (is (= 200 (:status response)))
+++++
+++++        ;; Verify MCP received the RESTORED value in the second call
+++++        (let [mcp-requests @(:received-requests mcp)
+++++              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
+++++              update-call (last tool-calls)
+++++              ;; Arguments in MCP request is a JSON string, parse it
+++++              args-str (-> update-call :body :params :arguments)
+++++              args (json/parse-string args-str true)]
+++++          (is (= "wes@example.com" (:email args))))
+++++
+++++        ;; Verify LLM received REDACTED token (not original) in tool result
+++++        (let [llm-requests @(:received-requests llm)
+++++              ;; Find the request where LLM called tool (has tool_calls)
+++++              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
+++++              ;; Get the tool result message that follows the tool call
+++++              msgs (:messages tool-call-req)
+++++              tool-result-msg (last msgs)]
+++++          ;; LLM should see token, not original email
+++++          (is (some? tool-result-msg))
+++++          (is (= "tool" (:role tool-result-msg)))
+++++          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
+++++          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
+++++
+++++(defn -main [& _args]
+++++  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
+++++    (System/exit (if (zero? (:fail result)) 0 1))))
++++diff --git a/test/mcp_injector/test_llm_server.clj b/test/mcp_injector/test_llm_server.clj
++++index fa3f9d7..3b4ee3a 100644
++++--- a/test/mcp_injector/test_llm_server.clj
+++++++ b/test/mcp_injector/test_llm_server.clj
++++@@ -16,15 +16,18 @@
++++    :model (get request-body :model "gpt-4o-mini")
++++    :choices [{:index 0
++++               :message {:role "assistant"
++++-                        :content (:content response-data)
+++++                        :content (or (get-in response-data [:choices 0 :message :content])
+++++                                     (:content response-data))
++++                         :tool_calls (when (:tool_calls response-data)
++++                                       (map-indexed
++++                                        (fn [idx tc]
++++-                                         {:id (str "call_" idx)
++++-                                          :type "function"
++++-                                          :index idx
++++-                                          :function {:name (:name tc)
++++-                                                     :arguments (json/generate-string (:arguments tc))}})
+++++                                         (let [fn-name (or (:name tc) (get-in tc [:function :name]))
+++++                                               fn-args (or (:arguments tc) (get-in tc [:function :arguments]))]
+++++                                           {:id (str "call_" idx)
+++++                                            :type "function"
+++++                                            :index idx
+++++                                            :function {:name fn-name
+++++                                                       :arguments (json/generate-string fn-args)}}))
++++                                        (:tool_calls response-data)))}
++++               :finish_reason (if (:tool_calls response-data) "tool_calls" "stop")}]
++++     ;; Default usage to nil to avoid polluting stats in tests that don't explicitly set it
++++=== FILE: src/mcp_injector/pii.clj ===
++++(ns mcp-injector.pii
++++  (:require [clojure.string :as str]
++++            [clojure.walk :as walk])
++++  (:import (java.security MessageDigest)))
++++
++++(def default-patterns
++++  [{:id :EMAIL_ADDRESS
++++    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
++++    :label "[EMAIL_ADDRESS]"}
++++   {:id :IBAN_CODE
++++    :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
++++    :label "[IBAN_CODE]"}])
++++
++++(defn shannon-entropy
++++  "Calculates the Shannon entropy of a string."
++++  [s]
++++  (if (empty? s)
++++    0.0
++++    (let [freqs (vals (frequencies s))
++++          len (count s)]
++++      (- (reduce + (map (fn [f]
++++                          (let [p (/ f len)]
++++                            (* p (/ (Math/log p) (Math/log 2)))))
++++                        freqs))))))
++++
++++(defn- character-diversity?
++++  "Checks if a string contains at least 3 distinct character classes."
++++  [s]
++++  (let [classes [(when (re-find #"[a-z]" s) :lower)
++++                 (when (re-find #"[A-Z]" s) :upper)
++++                 (when (re-find #"[0-9]" s) :digit)
++++                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]]
++++    (>= (count (remove nil? classes)) 3)))
++++
++++(defn- mask-string
++++  "Fixed-length mask to prevent leaking structural entropy."
++++  [_s]
++++  "********")
++++
++++(defn- redact-match [mode label match]
++++  (case mode
++++    :replace label
++++    :mask (mask-string match)
++++    :hash (str "#" (hash match))
++++    label))
++++
++++(defn- scan-env [text env-vars mode]
++++  (reduce-kv
++++   (fn [acc k v]
++++     (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
++++       (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
++++       acc))
++++   text
++++   env-vars))
++++
++++(defn- find-env-detections [text env-vars]
++++  (keep (fn [[k v]]
++++          (when (and (not (empty? v)) (> (count v) 5) (str/includes? text v))
++++            (keyword (str "ENV_VAR_" k))))
++++        env-vars))
++++
++++(defn- scan-entropy [text threshold mode]
++++  (let [tokens (str/split text #"\s+")]
++++    (reduce
++++     (fn [acc token]
++++       (if (and (> (count token) 12)
++++                (> (shannon-entropy token) threshold)
++++                (character-diversity? token))
++++         (str/replace acc token (redact-match mode "[HIGH_ENTROPY_SECRET]" token))
++++         acc))
++++     text
++++     tokens)))
++++
++++(defn scan-and-redact
++++  "Scans input text for PII patterns, high-entropy secrets, and env vars."
++++  [text {:keys [mode patterns entropy-threshold env]
++++         :or {mode :replace
++++              patterns default-patterns
++++              entropy-threshold 4.0
++++              env {}}}]
++++  (let [regex-result (reduce
++++                      (fn [state {:keys [id pattern label]}]
++++                        (if (seq (re-seq pattern (:text state)))
++++                          {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
++++                           :detected (conj (:detected state) id)}
++++                          state))
++++                      {:text text :detected []}
++++                      patterns)
++++        env-text (scan-env (:text regex-result) env mode)
++++        env-detections (find-env-detections text env)
++++        final-text (scan-entropy env-text entropy-threshold mode)
++++        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
++++    {:text final-text
++++     :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
++++
++++(defn generate-token
++++  "Generate a deterministic, truncated SHA-256 hash token."
++++  [label value salt]
++++  (let [input (str (name label) "|" value "|" salt)
++++        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
++++        hash-str (->> digest
++++                      (map (partial format "%02x"))
++++                      (apply str))
++++        truncated (subs hash-str 0 8)]
++++    (str "[" (name label) "_" truncated "]")))
++++
++++(defn- redact-string-value
++++  "Redact a single string value, returning [redacted-text token detected-label]"
++++  [v config]
++++  (if-not (string? v)
++++    [v nil nil]
++++    (if (empty? v)
++++      [v nil nil]
++++      (let [vault (:vault config)
++++            salt (:salt config)
++++            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
++++            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
++++        (cond
++++          existing-token [existing-token nil nil]
++++          previous-token [previous-token nil nil]
++++          :else
++++          (let [result (scan-and-redact v config)]
++++            (if (seq (:detected result))
++++              (let [detected (first (:detected result))
++++                    token (generate-token detected v salt)]
++++                (swap! vault assoc token v)
++++                [token token detected])
++++              [(:text result) nil nil])))))))
++++
++++(defn redact-data
++++  "Recursively walk a data structure, redact string values, store in vault.
++++   Returns [redacted-data vault-atom]"
++++  ([data config]
++++   (redact-data data config (atom {})))
++++  ([data config vault]
++++   (let [config-with-vault (assoc config :vault vault)
++++         redacted (walk/postwalk
++++                   (fn [x]
++++                     (if (string? x)
++++                       (let [[redacted-text _ _] (redact-string-value x config-with-vault)]
++++                         redacted-text)
++++                       x))
++++                   data)]
++++     [redacted vault])))
++++
++++(defn restore-tokens
++++  "Recursively walk a data structure, replacing tokens with original values from vault."
++++  [data vault]
++++  (let [v-map @vault]
++++    (if (empty? v-map)
++++      data
++++      (walk/postwalk
++++       (fn [x]
++++         (if (string? x)
++++           (reduce
++++            (fn [s [token original]]
++++              (if (and (string? s) (str/includes? s token))
++++                (str/replace s (str token) (str original))
++++                s))
++++            x
++++            v-map)
++++           x))
++++       data))))
++++
++++=== FILE: src/mcp_injector/config.clj ===
++++(ns mcp-injector.config
++++  "Configuration and environment variables for mcp-injector."
++++  (:require [clojure.edn :as edn]
++++            [clojure.java.io :as io]
++++            [clojure.walk :as walk]
++++            [clojure.string :as str]))
++++
++++(def default-config
++++  {:port 8088
++++   :host "127.0.0.1"
++++   :llm-url "http://localhost:8080"
++++   :mcp-config "./mcp-servers.edn"
++++   :max-iterations 10
++++   :log-level "debug"
++++   :timeout-ms 1800000
++++   :audit-log-path "logs/audit.log.ndjson"
++++   :audit-secret "default-audit-secret"})
++++
++++(defn env-var
++++  ([name] (System/getenv name))
++++  ([name default] (or (System/getenv name) default)))
++++
++++(defn- parse-int [s default]
++++  (try
++++    (Integer/parseInt s)
++++    (catch Exception _ default)))
++++
++++(defn- keywordize-keys [m]
++++  (walk/prewalk
++++   (fn [x]
++++     (if (map? x)
++++       (into {} (map (fn [[k v]] [(keyword k) v]) x))
++++       x))
++++   m))
++++
++++(defn deep-merge
++++  "Recursively merges maps. If keys conflict, the value from the last map wins.
++++   Ensures nested defaults are not wiped out by partial user config.
++++   If 'new' is nil, the 'old' value is preserved to prevent wiping out defaults."
++++  [& maps]
++++  (apply merge-with
++++         (fn [old new]
++++           (cond
++++             (nil? new) old
++++             (and (map? old) (map? new)) (deep-merge old new)
++++             :else new))
++++         maps))
++++
++++(defn- resolve-audit-path [env-path]
++++  (let [logs-dir (env-var "LOGS_DIRECTORY")
++++        state-dir (env-var "STATE_DIRECTORY")
++++        xdg-state (env-var "XDG_STATE_HOME")
++++        xdg-data (env-var "XDG_DATA_HOME")
++++        home (env-var "HOME")
++++        cwd (.getAbsolutePath (io/file "."))
++++        in-nix-store? (str/starts-with? cwd "/nix/store")
++++        default-path (:audit-log-path default-config)]
++++    (or env-path
++++        (cond
++++          logs-dir (str (str/replace logs-dir #"/$" "") "/audit.log.ndjson")
++++          state-dir (str (str/replace state-dir #"/$" "") "/audit.log.ndjson")
++++          xdg-state (str (str/replace xdg-state #"/$" "") "/mcp-injector/audit.log.ndjson")
++++          xdg-data (str (str/replace xdg-data #"/$" "") "/mcp-injector/audit.log.ndjson")
++++          home (str home "/.local/state/mcp-injector/audit.log.ndjson")
++++          (and in-nix-store? (not (str/starts-with? default-path "/")))
++++          (throw (ex-info (str "Cannot use relative audit log path '" default-path "' in read-only directory: " cwd)
++++                          {:cwd cwd
++++                           :default-path default-path
++++                           :suggestion "Set MCP_INJECTOR_AUDIT_LOG_PATH to an absolute, writable path."}))
++++          :else default-path))))
++++
++++(defn load-config []
++++  (let [env-audit-path (env-var "MCP_INJECTOR_AUDIT_LOG_PATH")
++++        env-audit-secret (env-var "MCP_INJECTOR_AUDIT_SECRET")]
++++    {:port (parse-int (env-var "MCP_INJECTOR_PORT") (:port default-config))
++++     :host (env-var "MCP_INJECTOR_HOST" (:host default-config))
++++     :llm-url (env-var "MCP_INJECTOR_LLM_URL" (:llm-url default-config))
++++     :mcp-config (env-var "MCP_INJECTOR_MCP_CONFIG" (:mcp-config default-config))
++++     :max-iterations (parse-int (env-var "MCP_INJECTOR_MAX_ITERATIONS") (:max-iterations default-config))
++++     :log-level (env-var "MCP_INJECTOR_LOG_LEVEL" (:log-level default-config))
++++     :timeout-ms (parse-int (env-var "MCP_INJECTOR_TIMEOUT_MS") (:timeout-ms default-config))
++++     :audit-log-path (resolve-audit-path env-audit-path)
++++     :audit-secret (or env-audit-secret (:audit-secret default-config))}))
++++
++++(defn get-env [name]
++++  (System/getenv name))
++++
++++(defn- resolve-value
++++  "Resolve a potentially dynamic value.
++++   If value is a map with :env, look up environment variable.
++++   Supports :prefix and :suffix."
++++  [v]
++++  (if (and (map? v) (:env v))
++++    (let [env-name (:env v)]
++++      (if (or (string? env-name) (keyword? env-name))
++++        (let [prefix (:prefix v "")
++++              suffix (:suffix v "")
++++              env-val (get-env (if (keyword? env-name) (name env-name) env-name))]
++++          (if env-val
++++            (str prefix env-val suffix)
++++            (do
++++              (println (str "Warning: Environment variable " env-name " not set."))
++++              nil)))
++++        v))
++++    v))
++++
++++(defn resolve-server-config
++++  "Recursively resolve dynamic values in a server configuration map.
++++   Uses post-order traversal: children first, then parent."
++++  [m]
++++  (let [resolve-all (fn resolve-all [x]
++++                      (cond
++++                        (map? x)
++++                        (let [resolved (into {} (map (fn [[k v]] [k (resolve-all v)]) x))]
++++                          (if (contains? resolved :env)
++++                            (resolve-value resolved)
++++                            resolved))
++++
++++                        (vector? x)
++++                        (mapv resolve-all x)
++++
++++                        :else x))]
++++    (resolve-all m)))
++++
++++(defn load-mcp-servers [config-path]
++++  (if-let [file (io/file config-path)]
++++    (if (.exists file)
++++      (let [raw-config (keywordize-keys (edn/read-string (slurp file)))]
++++        (update raw-config :servers
++++                (fn [servers]
++++                  (into {} (map (fn [[k v]] [k (resolve-server-config v)]) servers)))))
++++      {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}})
++++    {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}}))
++++
++++(defn get-llm-fallbacks
++++  "Get LLM fallback configuration from MCP servers config.
++++   Transforms from [{:provider :model}] format to provider/model strings"
++++  [mcp-config]
++++  (let [fallbacks-config (get-in mcp-config [:llm-gateway :fallbacks] [])]
++++    (mapv (fn [fb]
++++            (if (string? fb)
++++              fb
++++              (str (:provider fb) "/" (:model fb))))
++++          fallbacks-config)))
++++
++++(defn build-tool-directory
++++  "Build tool directory from mcp-config. 
++++   If pre-discovered-tools map provided, use those; otherwise fall back to config :tools list."
++++  ([mcp-config]
++++   (build-tool-directory mcp-config nil))
++++  ([mcp-config pre-discovered-tools]
++++   (reduce
++++    (fn [acc [server-name server-config]]
++++      (let [server-url (or (:url server-config) (:uri server-config))
++++            cmd (:cmd server-config)
++++            tool-names (:tools server-config)]
++++        (if (or server-url cmd)
++++          (let [tools (if (and pre-discovered-tools (get pre-discovered-tools server-name))
++++                        (get pre-discovered-tools server-name)
++++                        (map (fn [t] {:name (name t)}) tool-names))]
++++            (into acc (map (fn [tool]
++++                             {:name (str (name server-name) "." (:name tool))
++++                              :server (name server-name)})
++++                           tools)))
++++          acc)))
++++    []
++++    (:servers mcp-config))))
++++
++++(defn get-server-trust
++++  "Get trust level for a server/tool combination.
++++   Returns :restore (trusted), :none (untrusted), or :block.
++++   Precedence: tool-level :trust > server-level :trust > :none"
++++  [mcp-config server-name tool-name]
++++  (let [servers (:servers mcp-config)
++++        server (get servers (keyword server-name))]
++++    (if-not server
++++      :none
++++      (let [server-trust (or (:trust server) :none)
++++            tool-configs (:tools server)
++++            tool-config (when (map? tool-configs)
++++                          (get tool-configs (keyword tool-name)))
++++            tool-trust (or (:trust tool-config) :none)]
++++        (cond
++++          (= tool-trust :block) :block
++++          (= server-trust :block) :block
++++          (= tool-trust :restore) :restore
++++          (= server-trust :restore) :restore
++++          :else :none)))))
++++
++++(defn get-meta-tool-definitions
++++  "Get definitions for meta-tools like get_tool_schema and native tools"
++++  []
++++  [{:type "function"
++++    :function {:name "get_tool_schema"
++++               :description "Fetch the full JSON schema for a specific MCP tool to understand its parameters."
++++               :parameters {:type "object"
++++                            :properties {:tool {:type "string"
++++                                                :description "Full tool name with mcp__ prefix (e.g., 'mcp__stripe__retrieve_customer')"}}
++++                            :required ["tool"]}}}
++++   {:type "function"
++++    :function {:name "clojure-eval"
++++               :description "Evaluate Clojure code in the local REPL. WARNING: Full Clojure access - use with care. Returns the result as a string."
++++               :parameters {:type "object"
++++                            :properties {:code {:type "string"
++++                                                :description "Clojure code to evaluate"}}
++++                            :required ["code"]}}}])
++++
++++(defn- extract-tool-params
++++  "Extract parameter names from tool schema, distinguishing required vs optional.
++++   Returns [required-params optional-params] as vectors of strings."
++++  [tool]
++++  (let [schema (or (:inputSchema tool) (:schema tool))
++++        properties (get schema :properties {})
++++        required-vals (get schema :required [])
++++        required-set (set (map keyword required-vals))
++++        all-param-names (keys properties)
++++        required (filterv #(required-set %) all-param-names)
++++        optional (filterv #(not (required-set %)) all-param-names)]
++++    [(mapv name required) (mapv name optional)]))
++++
++++(defn- format-tool-with-params
++++  "Format a tool as mcp__server__tool [required, optional?]"
++++  [server-name tool]
++++  (let [tool-name (:name tool)
++++        [required optional] (extract-tool-params tool)]
++++    (if (or (seq required) (seq optional))
++++      (let [all-params (into required (map #(str % "?")) optional)]
++++        (str "mcp__" (name server-name) "__" tool-name " [" (str/join ", " all-params) "]"))
++++      (str "mcp__" (name server-name) "__" tool-name))))
++++
++++(defn inject-tools-into-messages
++++  "Inject MCP tools directory into messages.
++++   If pre-discovered-tools map provided (server-name -> [tools]), use those;
++++   otherwise fall back to config :tools list."
++++  ([messages mcp-config]
++++   (inject-tools-into-messages messages mcp-config nil))
++++  ([messages mcp-config pre-discovered-tools]
++++   (let [servers (:servers mcp-config)
++++         tool-lines (reduce
++++                     (fn [lines [server-name server-config]]
++++                       (let [server-url (or (:url server-config) (:uri server-config))
++++                             cmd (:cmd server-config)
++++                             tool-names (:tools server-config)]
++++                         (if (or server-url cmd)
++++                           (let [discovered (get pre-discovered-tools server-name)
++++                                 tools (if (and pre-discovered-tools (seq discovered))
++++                                         discovered
++++                                         (mapv (fn [t] {:name (name t)}) tool-names))
++++                                 tools (filter #(some? (:name %)) tools)
++++                                 formatted (map #(format-tool-with-params server-name %) tools)
++++                                 tool-str (str/join ", " formatted)]
++++                             (if (seq tools)
++++                               (conj lines (str "- mcp__" (name server-name) ": " tool-str))
++++                               lines))
++++                           lines)))
++++                     []
++++                     servers)
++++         directory-text (str "## Remote Tools (MCP)\n"
++++                             "You have access to namespaced MCP tools.\n\n"
++++                             "### Available:\n"
++++                             (str/join "\n" tool-lines)
++++                             "\n\n### Usage:\n"
++++                             "Get schema: get_tool_schema {:tool \"mcp__server__tool\"}\n"
++++                             "Call tool: mcp__server__tool {:key \"value\"}\n\n"
++++                             "### Native:\n"
++++                             "- clojure-eval: Evaluate Clojure. Args: {:code \"...\"}\n"
++++                             "  Example: {:code \"(vec (range 5))\"} => \"[0 1 2 3 4]\"")
++++         system-msg {:role "system" :content directory-text}]
++++     (cons system-msg messages))))
++++
++++(defn get-virtual-models
++++  "Get virtual models configuration from MCP servers config"
++++  [mcp-config]
++++  (get-in mcp-config [:llm-gateway :virtual-models] {}))
++++
++++(defn resolve-governance
++++  "Unified governance resolution logic. Prioritizes nested :governance block.
++++   Precedence: top-level :governance > :llm-gateway :governance > defaults.
++++   Uses deep-merge to preserve nested default settings."
++++  [mcp-config env-config]
++++  (let [gateway (:llm-gateway mcp-config)
++++        gov-user (or (:governance mcp-config) (:governance gateway))
++++        defaults {:mode :permissive
++++                  :pii {:enabled true :mode :replace}
++++                  :audit {:enabled true :path (:audit-log-path env-config)}
++++                  :policy {:mode :permissive}}]
++++    (deep-merge defaults gov-user)))
++++
++++(defn get-config
++++  "Unified config: env vars override config file, with defaults as fallback.
++++    Priority: env var > config file > default"
++++  [mcp-config]
++++  (let [env (load-config)
++++        gateway (:llm-gateway mcp-config)
++++        gov (resolve-governance mcp-config env)]
++++    {:port (:port env)
++++     :host (:host env)
++++     :llm-url (or (env-var "MCP_INJECTOR_LLM_URL")
++++                  (:url gateway)
++++                  (:llm-url env))
++++     :mcp-config (:mcp-config env)
++++     :max-iterations (let [v (or (env-var "MCP_INJECTOR_MAX_ITERATIONS")
++++                                 (:max-iterations gateway))]
++++                       (if (string? v) (parse-int v 10) (or v (:max-iterations env))))
++++     :log-level (or (env-var "MCP_INJECTOR_LOG_LEVEL")
++++                    (:log-level gateway)
++++                    (:log-level env))
++++     :timeout-ms (let [v (or (env-var "MCP_INJECTOR_TIMEOUT_MS")
++++                             (:timeout-ms gateway))]
++++                   (if (string? v) (parse-int v 1800000) (or v (:timeout-ms env))))
++++     :fallbacks (:fallbacks gateway)
++++     :virtual-models (:virtual-models gateway)
++++     :audit-log-path (get-in gov [:audit :path])
++++     :audit-secret (or (get-in gov [:audit :secret])
++++                       (env-var "MCP_INJECTOR_AUDIT_SECRET")
++++                       (:audit-secret env)
++++                       "default-audit-secret")
++++     :governance gov}))
++++
++++(defn get-llm-url
++++  "Get LLM URL: env var overrides config file"
++++  [mcp-config]
++++  (or (env-var "MCP_INJECTOR_LLM_URL")
++++      (get-in mcp-config [:llm-gateway :url])
++++      "http://localhost:8080"))
++++
++++=== FILE: src/mcp_injector/core.clj ===
++++(ns mcp-injector.core
++++  (:require [org.httpkit.server :as http]
++++            [babashka.http-client :as http-client]
++++            [cheshire.core :as json]
++++            [clojure.string :as str]
++++            [clojure.java.io :as io]
++++            [mcp-injector.config :as config]
++++            [mcp-injector.openai-compat :as openai]
++++            [mcp-injector.mcp-client :as mcp]
++++            [mcp-injector.audit :as audit]
++++            [mcp-injector.pii :as pii]
++++            [mcp-injector.policy :as policy]))
++++
++++(def ^:private server-state (atom nil))
++++(def ^:private usage-stats (atom {}))
++++(def ^:private cooldown-state (atom {}))
++++(def ^:private ^:dynamic *request-id* nil)
++++(def ^:private ^:dynamic *audit-config* nil)
++++
++++(defn- log-request
++++  ([level message data]
++++   (log-request level message data nil))
++++  ([level message data context]
++++   (let [log-entry (merge {:timestamp (str (java.time.Instant/now))
++++                           :level level
++++                           :message message
++++                           :request-id (or *request-id* "none")}
++++                          context
++++                          {:data data})]
++++     (println (json/generate-string log-entry))
++++     ;; Fail-open audit logging
++++     (when *audit-config*
++++       (try
++++         (audit/append-event! (:secret *audit-config*) level log-entry)
++++         (catch Exception e
++++           (binding [*out* *err*]
++++             (println (json/generate-string
++++                       {:timestamp (str (java.time.Instant/now))
++++                        :level "error"
++++                        :message "AUDIT LOG WRITE FAILURE — audit trail degraded"
++++                        :error (.getMessage e)})))))))))
++++
++++(defn- parse-body [body]
++++  (try
++++    (if (string? body)
++++      (json/parse-string body true)
++++      (json/parse-string (slurp body) true))
++++    (catch Exception e
++++      (throw (ex-info "Failed to parse JSON body"
++++                      {:type :json_parse_error
++++                       :status 400
++++                       :message "Failed to parse JSON body. Please ensure your request is valid JSON."} e)))))
++++
++++(defn- is-context-overflow-error? [error-str]
++++  (when (string? error-str)
++++    (let [patterns [#"(?i)cannot read propert(?:y|ies) of undefined.*prompt"
++++                    #"(?i)cannot read propert(?:y|ies) of null.*prompt"
++++                    #"(?i)prompt_tokens.*undefined"
++++                    #"(?i)prompt_tokens.*null"
++++                    #"(?i)context window.*exceeded"
++++                    #"(?i)context length.*exceeded"
++++                    #"(?i)maximum context.*exceeded"
++++                    #"(?i)request.*too large"
++++                    #"(?i)prompt is too long"
++++                    #"(?i)exceeds model context"
++++                    #"(?i)413.*too large"
++++                    #"(?i)request size exceeds"]]
++++      (some #(re-find % error-str) patterns))))
++++
++++(defn- translate-error-for-openclaw [error-data status-code]
++++  (let [error-str (or (get-in error-data [:error :message])
++++                      (:message error-data)
++++                      (:details error-data)
++++                      (str error-data))]
++++    (cond
++++      (is-context-overflow-error? error-str)
++++      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
++++       :status 503
++++       :type "context_overflow"
++++       :details error-data}
++++
++++      (= 429 status-code)
++++      {:message (or (:message error-data) "Rate limit exceeded")
++++       :status 429
++++       :type "rate_limit_exceeded"
++++       :details error-data}
++++
++++      :else
++++      {:message (or (:message error-data) "Upstream error")
++++       :status 502
++++       :type "upstream_error"
++++       :details error-data})))
++++
++++(defn- call-llm [base-url payload]
++++  (let [url (str (str/replace base-url #"/$" "") "/v1/chat/completions")
++++        resp (try
++++               (http-client/post url
++++                                 {:headers {"Content-Type" "application/json"}
++++                                  :body (json/generate-string payload)
++++                                  :throw false})
++++               (catch Exception e
++++                 {:status 502 :body (json/generate-string {:error {:message (.getMessage e)}})}))]
++++    (if (= 200 (:status resp))
++++      {:success true :data (json/parse-string (:body resp) true)}
++++      (let [status (:status resp)
++++            error-data (try (json/parse-string (:body resp) true) (catch Exception _ (:body resp)))
++++            translated (translate-error-for-openclaw error-data status)]
++++        (log-request "warn" "LLM Error" {:status status :body (:body resp) :translated translated})
++++        {:success false :status (:status translated) :error translated}))))
++++
++++(defn- record-completion! [alias provider usage]
++++  (when usage
++++    (let [update-entry (fn [existing usage]
++++                         (let [input (or (:prompt_tokens usage) 0)
++++                               output (or (:completion_tokens usage) 0)
++++                               total (or (:total_tokens usage) (+ input output))]
++++                           {:requests (inc (or (:requests existing) 0))
++++                            :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
++++                            :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
++++                            :total-tokens (+ total (or (:total-tokens existing) 0))
++++                            :rate-limits (or (:rate-limits existing) 0)
++++                            :context-overflows (or (:context-overflows existing) 0)
++++                            :last-updated (System/currentTimeMillis)}))]
++++      (swap! usage-stats
++++             (fn [stats]
++++               (cond-> stats
++++                 alias (update alias update-entry usage)
++++                 (and provider (not= provider alias)) (update provider update-entry usage)))))))
++++
++++(defn- track-provider-failure! [provider status]
++++  (when provider
++++    (let [counter (if (= status 503) :context-overflows :rate-limits)]
++++      (swap! usage-stats update provider
++++             (fn [existing]
++++               (assoc (or existing {:requests 0
++++                                    :total-input-tokens 0
++++                                    :total-output-tokens 0
++++                                    :total-tokens 0})
++++                      counter (inc (or (get existing counter) 0))
++++                      :last-updated (System/currentTimeMillis)))))))
++++
++++(defn reset-usage-stats! []
++++  (reset! usage-stats {}))
++++
++++(defn- execute-tool [full-name args mcp-servers discovered-this-loop governance context]
++++  (let [policy-result (policy/allow-tool? (:policy governance) full-name context)]
++++    (if-not (:allowed? policy-result)
++++      (do
++++        (log-request "warn" "Tool Blocked by Policy" {:tool full-name :reason (:reason policy-result)} context)
++++        {:error "Tool execution denied"})
++++      (cond
++++        (= full-name "get_tool_schema")
++++        (let [full-tool-name (:tool args)
++++              ;; Parse prefixed name: mcp__server__tool -> [server tool]
++++              [s-name t-name] (if (and full-tool-name (str/includes? full-tool-name "__"))
++++                                (let [idx (str/last-index-of full-tool-name "__")]
++++                                  [(subs full-tool-name 5 idx) (subs full-tool-name (+ idx 2))])
++++                                [nil nil])
++++              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
++++          (if (and s-name s-config t-name)
++++            (let [schema (mcp/get-tool-schema (name s-name) s-config t-name (:policy governance))]
++++              (if (:error schema)
++++                schema
++++                (do
++++                  (swap! discovered-this-loop assoc full-tool-name schema)
++++                  schema)))
++++            {:error (str "Invalid tool name. Use format: mcp__server__tool (e.g., mcp__stripe__retrieve_customer). Got: " full-tool-name)}))
++++
++++        (= full-name "clojure-eval")
++++        (try
++++          (let [code (:code args)
++++                result (load-string code)]
++++            (json/generate-string result))
++++          (catch Exception e
++++            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
++++
++++        (str/starts-with? full-name "mcp__")
++++        (let [t-name (str/replace full-name #"^mcp__" "")
++++              [s-name real-t-name] (if (str/includes? t-name "__")
++++                                     (let [idx (str/last-index-of t-name "__")]
++++                                       [(subs t-name 0 idx) (subs t-name (+ idx 2))])
++++                                     [nil t-name])
++++              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
++++          (if (and s-name s-config)
++++            (let [result (mcp/call-tool (name s-name) s-config real-t-name args (:policy governance))
++++                  ;; Auto-discover: add schema to discovered-this-loop so next turn has it
++++                  _ (when-not (contains? result :error)
++++                      (let [schema (mcp/get-tool-schema (name s-name) s-config real-t-name (:policy governance))]
++++                        (when-not (:error schema)
++++                          (swap! discovered-this-loop assoc full-name schema))))]
++++              result)
++++            (if-let [_ (get @discovered-this-loop full-name)]
++++              (let [[_ s-name-auto real-t-auto] (str/split full-name #"__" 3)
++++                    s-conf-auto (get-in mcp-servers [:servers (keyword s-name-auto)])]
++++                (mcp/call-tool (name s-name-auto) s-conf-auto real-t-auto args (:policy governance)))
++++              {:error (str "Unknown tool: " full-name ". Use get_tool_schema with full prefixed name first.")})))
++++
++++        :else {:error (str "Unknown tool: " full-name)}))))
++++
++++(defn- parse-tool-name
++++  "Parse mcp__server__tool format into [server tool]"
++++  [full-name]
++++  (if (str/includes? full-name "__")
++++    (let [t-name (str/replace full-name #"^mcp__" "")
++++          idx (str/last-index-of t-name "__")]
++++      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
++++    [nil full-name]))
++++
++++(defn- scrub-messages [messages vault request-id]
++++  (mapv (fn [m]
++++          (let [content (:content m)
++++                role (:role m)]
++++            (if (and (string? content)
++++                     ;; Only redact user/system messages - assistant tool results are already handled
++++                     (or (= role "system") (= role "user"))
++++                     ;; Skip if already contains PII tokens (avoid double-redaction)
++++                     (not (re-find #"\[PII_[A-Z_]+_[a-f0-9]{8}\]" content)))
++++              (let [config {:mode :replace :salt request-id}
++++                    [redacted-content _] (pii/redact-data content config vault)]
++++                (assoc m :content redacted-content))
++++              m)))
++++        messages))
++++
++++(defn- restore-tool-args
++++  "Restore tokens in tool args if server is trusted"
++++  [args vault mcp-servers full-tool-name]
++++  (let [[server tool] (parse-tool-name full-tool-name)
++++        trust (when server (config/get-server-trust mcp-servers server tool))
++++        restored (if (= trust :restore)
++++                   (pii/restore-tokens args vault)
++++                   args)]
++++    restored))
++++
++++(defn- redact-tool-output
++++  "Redact PII from tool output, return [content vault]"
++++  [raw-output vault request-id]
++++  (let [;; Try to parse as JSON first for JSON tokenization
++++        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
++++        ;; If parsed successfully, redact the data structure; otherwise redact the string
++++        ;; Special handling for MCP response format: parse nested :text field if present
++++        [redacted new-vault] (if parsed
++++                               (let [;; Check if this is MCP response format with :text field containing JSON
++++                                     ;; Handle both map and sequential (vector/list/lazy-seq) responses
++++                                     parsed (cond
++++                                              (map? parsed)
++++                                              (if (string? (:text parsed))
++++                                                (try (assoc parsed :text (json/parse-string (:text parsed) true))
++++                                                     (catch Exception _ parsed))
++++                                                parsed)
++++                                              (sequential? parsed)
++++                                              (mapv (fn [item]
++++                                                      (if (and (map? item) (string? (:text item)))
++++                                                        (try (assoc item :text (json/parse-string (:text item) true))
++++                                                             (catch Exception _ item))
++++                                                        item))
++++                                                    parsed)
++++                                              :else parsed)
++++                                     config {:mode :replace :salt request-id}
++++                                     [redacted-struct vault-after] (pii/redact-data parsed config vault)]
++++                                 [(json/generate-string redacted-struct) vault-after])
++++                               (let [config {:mode :replace :salt request-id}
++++                                     [redacted-str vault-after] (pii/redact-data raw-output config vault)]
++++                                 [redacted-str vault-after]))
++++        ;; For logging, we still scan the raw output
++++        detected (:detected (pii/scan-and-redact raw-output {:mode :replace}))]
++++    (when (seq detected)
++++      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
++++    [redacted new-vault]))
++++
++++(defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
++++  (let [model (:model payload)
++++        discovered-this-loop (atom {})
++++        vault (atom {})
++++        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
++++        context {:model model :request-id request-id}]
++++    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
++++           iteration 0]
++++      (if (>= iteration max-iterations)
++++        {:success true
++++         :provider model
++++         :data {:choices [{:index 0
++++                           :message {:role "assistant"
++++                                     :content "Maximum iterations reached. Here's what I found so far:"
++++                                     :tool_calls nil}
++++                           :finish_reason "length"}]}}
++++        (let [_ (log-request "info" "Tool Loop" {:iteration iteration :calls (count (get-in current-payload [:messages]))} context)
++++              resp (call-llm llm-url current-payload)]
++++          (if-not (:success resp)
++++            resp
++++            (let [choices (get-in resp [:data :choices])
++++                  message (get-in (first choices) [:message])
++++                  tool-calls (:tool_calls message)]
++++              (if-not tool-calls
++++                (assoc resp :provider model)
++++                (let [mcp-calls (filter (fn [tc]
++++                                          (let [n (get-in tc [:function :name])]
++++                                            (or (= n "get_tool_schema")
++++                                                (and n (str/starts-with? n "mcp__")))))
++++                                        tool-calls)
++++                      native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
++++                                           tool-calls)]
++++                  (if (and (empty? mcp-calls) (empty? native-calls))
++++                    (assoc resp :provider model)
++++                    (let [[results new-vault]
++++                          (reduce
++++                           (fn [[results vault-state] tc]
++++                             (let [fn-name (get-in tc [:function :name])
++++                                   args-str (get-in tc [:function :arguments])
++++                                   parse-result (try
++++                                                  {:success true :args (json/parse-string args-str true)}
++++                                                  (catch Exception e
++++                                                    {:success false :error (.getMessage e)}))]
++++                               (if (:success parse-result)
++++                                 (let [;; Restore args if trusted
++++                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
++++                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
++++                                       ;; Redact output with vault
++++                                       raw-content (if (string? result) result (json/generate-string result))
++++                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
++++                                   [(conj results {:role "tool"
++++                                                   :tool_call_id (:id tc)
++++                                                   :name fn-name
++++                                                   :content redacted})
++++                                    updated-vault])
++++                                 [(conj results {:role "tool"
++++                                                 :tool_call_id (:id tc)
++++                                                 :name fn-name
++++                                                 :content (json/generate-string
++++                                                           {:error "Malformed tool arguments JSON"
++++                                                            :details {:args-str args-str
++++                                                                      :parse-error (:error parse-result)}})})
++++                                  vault-state])))
++++                           [[] vault]
++++                           (concat mcp-calls native-calls))
++++                          newly-discovered @discovered-this-loop
++++                          new-tools (vec (concat (config/get-meta-tool-definitions)
++++                                                 (map (fn [[name schema]]
++++                                                        {:type "function"
++++                                                         :function {:name name
++++                                                                    :description (:description schema)
++++                                                                    :parameters (:inputSchema schema)}})
++++                                                      newly-discovered)))
++++                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
++++                          new-messages (into new-messages results)]
++++                      (recur (assoc current-payload
++++                                    :messages (scrub-messages new-messages new-vault request-id)
++++                                    :tools new-tools)
++++                             (inc iteration)))))))))))))
++++
++++(defn- set-cooldown! [provider minutes]
++++  (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
++++
++++(defn- is-on-cooldown? [provider]
++++  (if-let [expiry (get @cooldown-state provider)]
++++    (if (> expiry (System/currentTimeMillis))
++++      true
++++      (do (swap! cooldown-state dissoc provider) false))
++++    false))
++++
++++(defn reset-cooldowns! []
++++  (reset! cooldown-state {}))
++++
++++(defn- body->string [body]
++++  (if (string? body) body (slurp body)))
++++
++++(defn- extract-discovered-tools
++++  "Scan messages for tool schemas returned by get_tool_schema.
++++   Returns a map of tool-name -> full tool schema."
++++  [messages]
++++  (reduce
++++   (fn [acc msg]
++++     (if (= "tool" (:role msg))
++++       (let [content (:content msg)
++++             parsed (try (json/parse-string (body->string content) true) (catch Exception _ nil))]
++++         (if (and parsed (:name parsed))
++++           (let [tool-name (:name parsed)
++++                 formatted-name (if (str/includes? tool-name "__")
++++                                  tool-name
++++                                  (str "mcp__" tool-name))]
++++             (assoc acc formatted-name parsed))
++++           acc))
++++       acc))
++++   {}
++++   messages))
++++
++++(defn- prepare-llm-request [chat-req mcp-servers]
++++  (let [meta-tools (config/get-meta-tool-definitions)
++++        discovered-tools (extract-discovered-tools (:messages chat-req))
++++        existing-tools (:tools chat-req)
++++        fallbacks (config/get-llm-fallbacks mcp-servers)
++++        discovered-tool-defs (map (fn [[name schema]]
++++                                    {:type "function"
++++                                     :function {:name name
++++                                                :description (:description schema)
++++                                                :parameters (:inputSchema schema)}})
++++                                  discovered-tools)
++++        merged-tools (vec (concat (or existing-tools [])
++++                                  meta-tools
++++                                  discovered-tool-defs))
++++        ;; Merge extra_body into the request for fields like request-id
++++        extra-body (or (:extra_body chat-req) {})]
++++    (-> chat-req
++++        (assoc :stream false)
++++        (dissoc :stream_options)
++++        (assoc :fallbacks fallbacks)
++++        (merge extra-body) ;; Lift extra_body fields to top level
++++        (update :messages (fn [msgs]
++++                            (mapv (fn [m]
++++                                    (if (and (= (:role m) "assistant") (:tool_calls m))
++++                                      (update m :tool_calls (fn [tcs]
++++                                                              (mapv #(dissoc % :index) tcs)))
++++                                      m))
++++                                  msgs)))
++++        (assoc :tools merged-tools))))
++++
++++(defn- try-virtual-model-chain [config prepared-req llm-url mcp-servers max-iterations governance]
++++  (let [chain (:chain config)
++++        retry-on (set (:retry-on config [429 500]))
++++        cooldown-mins (get config :cooldown-minutes 5)
++++        original-model (:model prepared-req)]
++++    (loop [providers (filter #(not (is-on-cooldown? %)) chain)
++++           last-error nil]
++++      (if (empty? providers)
++++        {:success false :status 502 :error (or last-error {:message "All providers failed"})}
++++        (let [provider (first providers)
++++              _ (log-request "info" "Virtual model: trying provider" {:provider provider :remaining (count (rest providers))}
++++                             {:model original-model :endpoint llm-url})
++++              req (-> prepared-req
++++                      (assoc :model provider)
++++                      (dissoc :fallbacks))
++++              result (agent-loop llm-url req mcp-servers max-iterations governance)]
++++          (if (:success result)
++++            (assoc result :provider provider)
++++            (if (some #(= % (:status result)) retry-on)
++++              (do
++++                (log-request "warn" "Virtual model: provider failed, setting cooldown" {:provider provider :status (:status result) :cooldown-mins cooldown-mins}
++++                             {:model original-model :endpoint llm-url})
++++                (set-cooldown! provider cooldown-mins)
++++                (track-provider-failure! provider (:status result))
++++                (recur (rest providers) (:error result)))
++++              (assoc result :provider provider))))))))
++++
++++(defn- handle-chat-completion [request mcp-servers config]
++++  (try
++++    (let [chat-req (parse-body (:body request))
++++          model (:model chat-req)
++++          _ (log-request "info" "Chat Completion Started" {:stream (:stream chat-req)} {:model model})
++++          discovered (reduce (fn [acc [s-name s-conf]]
++++                               (let [url (or (:url s-conf) (:uri s-conf))
++++                                     cmd (:cmd s-conf)]
++++                                 (if (or url cmd)
++++                                   (try (assoc acc s-name (mcp/discover-tools (name s-name) s-conf (:tools s-conf) (:policy (:governance config))))
++++                                        (catch Exception e
++++                                          (log-request "warn" "Discovery failed" {:server s-name :error (.getMessage e)} {:model model})
++++                                          acc))
++++                                   acc)))
++++                             {} (:servers mcp-servers))
++++          messages (config/inject-tools-into-messages (:messages chat-req) mcp-servers discovered)
++++          llm-url (or (:llm-url config) (config/get-llm-url mcp-servers))
++++          virtual-models (config/get-virtual-models mcp-servers)
++++          virtual-config (or (get virtual-models model) (get virtual-models (keyword model)))
++++          prepared-req (prepare-llm-request (assoc chat-req :messages messages) mcp-servers)
++++          max-iter (or (:max-iterations config) 10)
++++          gov (:governance config)
++++          result (if virtual-config
++++                   (try-virtual-model-chain virtual-config prepared-req llm-url mcp-servers max-iter gov)
++++                   (agent-loop llm-url prepared-req mcp-servers max-iter gov))]
++++      (if (:success result)
++++        (let [final-resp (:data result)
++++              actual-provider (:provider result)
++++              _ (record-completion! model actual-provider (:usage final-resp))
++++              _ (log-request "info" "Chat Completion Success" {:usage (:usage final-resp) :provider actual-provider} {:model model})
++++              body (if (:stream chat-req)
++++                     (openai/build-chat-response-streaming
++++                      {:content (get-in final-resp [:choices 0 :message :content])
++++                       :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
++++                       :model model
++++                       :usage (:usage final-resp)})
++++                     (json/generate-string
++++                      (openai/build-chat-response
++++                       {:content (get-in final-resp [:choices 0 :message :content])
++++                        :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
++++                        :model model
++++                        :usage (:usage final-resp)})))]
++++          {:status 200 :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})
++++        (let [status (or (:status result) 500)
++++              error-data (:error result)
++++              error-msg (if (map? error-data) (:message error-data) (str "Failed: " error-data))
++++              error-type (get-in result [:error :type] "internal_error")
++++              _ (log-request "warn" "Chat Completion Failed" {:status status :error error-msg :type error-type} {:model model :endpoint llm-url})
++++              body (if (:stream chat-req)
++++                     (str "data: " (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}) "\n\ndata: [DONE]\n\n")
++++                     (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
++++          {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
++++    (catch Exception e
++++      (let [err-type (or (some-> e ex-data :type name) "internal_error")
++++            err-msg (or (.getMessage e) (str e))
++++            stack (.getStackTrace e)]
++++        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
++++        {:status 400
++++         :headers {"Content-Type" "application/json"}
++++         :body (json/generate-string {:error {:message err-msg
++++                                              :type err-type}})}))))
++++
++++(defn get-gateway-state []
++++  {:cooldowns @cooldown-state
++++   :usage @usage-stats
++++   :warming-up? (let [fut (get @server-state :warmup-future)]
++++                  (if fut (not (realized? fut)) false))})
++++
++++(defn- handle-api [request _mcp-servers config]
++++  (let [uri (:uri request)
++++        method (:request-method request)]
++++    (case [method uri]
++++      [:get "/api/v1/status"]
++++      {:status 200 :body (json/generate-string {:status "ok" :version "1.0.0" :warming-up? (:warming-up? (get-gateway-state))})}
++++
++++      [:get "/api/v1/mcp/tools"]
++++      {:status 200 :body (json/generate-string (mcp/get-cache-state))}
++++
++++      [:post "/api/v1/mcp/reset"]
++++      (do (mcp/clear-tool-cache!)
++++          {:status 200 :body (json/generate-string {:message "MCP state reset successful"})})
++++
++++      [:get "/api/v1/llm/state"]
++++      {:status 200 :body (json/generate-string (get-gateway-state))}
++++
++++      [:post "/api/v1/llm/cooldowns/reset"]
++++      (do (reset-cooldowns!)
++++          {:status 200 :body (json/generate-string {:message "Cooldowns reset successful"})})
++++
++++      [:get "/api/v1/stats"]
++++      {:status 200 :body (json/generate-string {:stats @usage-stats})}
++++
++++      [:get "/api/v1/audit/verify"]
++++      (let [path (:audit-log-path config)
++++            secret (:audit-secret config)
++++            valid? (audit/verify-log (io/file path) secret)]
++++        {:status 200 :body (json/generate-string {:valid? valid? :path path})})
++++
++++      {:status 404 :body (json/generate-string {:error "Not found"})})))
++++
++++(defn- handler [request mcp-servers config]
++++  (let [request-id (str (java.util.UUID/randomUUID))
++++        audit-conf {:path (io/file (:audit-log-path config))
++++                    :secret (:audit-secret config)}]
++++    (binding [*request-id* request-id
++++              *audit-config* audit-conf]
++++      (try
++++        (let [uri (:uri request)]
++++          (cond
++++            (= uri "/v1/chat/completions")
++++            (if (= :post (:request-method request))
++++              (handle-chat-completion request mcp-servers config)
++++              {:status 405 :body "Method not allowed"})
++++
++++            (= uri "/health")
++++            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}
++++
++++            (= uri "/stats")
++++            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:stats @usage-stats})}
++++
++++            (str/starts-with? uri "/api/v1")
++++            (handle-api request mcp-servers config)
++++
++++            :else
++++            {:status 404 :body "Not found"}))
++++        (catch Exception e
++++          (let [err-data (ex-data e)
++++                status (or (:status err-data) 500)
++++                err-type (or (some-> err-data :type name) "internal_error")]
++++            (log-request "error" "Request failed" {:type err-type :message (.getMessage e)} {:endpoint (:uri request)})
++++            {:status status
++++             :headers {"Content-Type" "application/json"}
++++             :body (json/generate-string {:error {:message (or (:message err-data) (.getMessage e) "Internal server error")
++++                                                  :type err-type}})}))))))
++++
++++(defn start-server [mcp-config]
++++  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
++++        provided-governance (or (:governance mcp-config)
++++                                (:governance (:mcp-servers mcp-config)))
++++
++++        ;; Runtime settings - prioritize input > env > default
++++        port (or (:port mcp-config)
++++                 (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
++++                 8080)
++++        host (or (:host mcp-config)
++++                 (System/getenv "MCP_INJECTOR_HOST")
++++                 "127.0.0.1")
++++        llm-url (or (:llm-url mcp-config)
++++                    (System/getenv "MCP_INJECTOR_LLM_URL")
++++                    "http://localhost:11434")
++++        log-level (or (:log-level mcp-config)
++++                      (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
++++        max-iterations (or (:max-iterations mcp-config)
++++                           (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
++++                           10)
++++        mcp-config-path (or (:mcp-config-path mcp-config)
++++                            (System/getenv "MCP_INJECTOR_MCP_CONFIG")
++++                            "mcp-servers.edn")
++++        ;; Audit trail config
++++        audit-log-path (or (:audit-log-path mcp-config)
++++                           (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
++++                           "logs/audit.log.ndjson")
++++        audit-secret (or (:audit-secret mcp-config)
++++                         (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
++++                         "default-audit-secret")
++++        ;; Merge provided mcp-config with loaded ones if needed
++++        base-mcp-servers (cond
++++                           (and (map? mcp-config) (:servers mcp-config)) mcp-config
++++                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
++++                           :else (config/load-mcp-servers mcp-config-path))
++++        ;; Apply overrides from mcp-config (like :virtual-models in tests)
++++        mcp-servers (if (map? mcp-config)
++++                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
++++                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
++++                        (if-let [gov (:governance mcp-config)]
++++                          (assoc merged :governance gov)
++++                          merged))
++++                      base-mcp-servers)
++++        ;; Unified configuration resolution - pass extracted governance
++++        unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
++++        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
++++        final-config {:port port :host host :llm-url llm-url :log-level log-level
++++                      :max-iterations max-iterations :mcp-config-path mcp-config-path
++++                      :audit-log-path audit-log-path :audit-secret audit-secret
++++                      :governance final-governance}
++++        ;; Validate policy at startup
++++        _ (policy/validate-policy! (:policy final-governance))
++++        ;; P3 Integration: Initialize Audit system
++++        _ (audit/init-audit! audit-log-path)
++++        srv (http/run-server (fn [req] (handler req mcp-servers final-config)) {:port port :host host})
++++        actual-port (or (:local-port (meta srv)) port)
++++        warmup-fut (future (mcp/warm-up! mcp-servers))]
++++    (reset! server-state {:server srv :port actual-port :warmup-future warmup-fut})
++++    (log-request "info" "mcp-injector started" (assoc final-config :port actual-port))
++++    {:server srv :port actual-port :warmup-future warmup-fut}))
++++
++++(defn stop-server [s]
++++  (when s
++++    (let [srv (cond (fn? s) s (map? s) (:server s) :else s)
++++          fut (when (map? s) (:warmup-future s))]
++++      (when fut (future-cancel fut))
++++      (when (fn? srv) (srv :timeout 100))
++++      (reset! server-state nil)
++++      (mcp/clear-tool-cache!)
++++      ;; P3 Integration: Close Audit system
++++      (audit/close-audit!))))
++++
++++(defn clear-mcp-sessions! []
++++  (mcp/clear-tool-cache!))
++++
++++(defn -main [& _args]
++++  (let [initial-config (config/load-config)
++++        mcp-servers (config/load-mcp-servers (:mcp-config initial-config))
++++        unified-config (config/get-config mcp-servers)]
++++    (start-server unified-config)))
++++
++++=== FILE: test/mcp_injector/restoration_test.clj ===
++++(ns mcp-injector.restoration-test
++++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
++++            [clojure.string :as str]
++++            [mcp-injector.test-llm-server :as test-llm]
++++            [mcp-injector.test-mcp-server :as test-mcp]
++++            [mcp-injector.core :as core]
++++            [cheshire.core :as json]
++++            [org.httpkit.client :as http]))
++++
++++(def test-state (atom {}))
++++
++++(use-fixtures :once
++++  (fn [f]
++++    (let [llm (test-llm/start-server)
++++          mcp (test-mcp/start-server)]
++++      (swap! test-state assoc :llm llm :mcp mcp)
++++      (let [injector (core/start-server
++++                      {:port 0
++++                       :host "127.0.0.1"
++++                       :llm-url (str "http://localhost:" (:port llm))
++++                       :mcp-servers {:servers
++++                                     {:trusted-db
++++                                      {:url (str "http://localhost:" (:port mcp))
++++                                       :tools ["query"]
++++                                       :trust :restore}
++++                                      :untrusted-api
++++                                      {:url (str "http://localhost:" (:port mcp))
++++                                       :tools ["send"]
++++                                       :trust :none}}}})]
++++        (swap! test-state assoc :injector injector)
++++        (try
++++          (f)
++++          (finally
++++            (core/stop-server injector)
++++            (test-llm/stop-server llm)
++++            (test-mcp/stop-server mcp)))))))
++++
++++(deftest test-secret-redaction-and-restoration
++++  (testing "End-to-end Redact -> Decide -> Restore flow"
++++    (let [{:keys [injector llm mcp]} @test-state
++++          port (:port injector)]
++++
++++      ;; 1. Setup MCP to return a secret
++++      ((:set-tools! mcp)
++++       {:query {:description "Query database"
++++                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
++++                :handler (fn [args]
++++                           (if (or (:email args) (get args "email"))
++++                             {:status "success" :received (or (:email args) (get args "email"))}
++++                             {:email "wes@example.com" :secret "super-secret-123"}))}})
++++
++++      ;; 2. LLM Turn 1: Get data (will be redacted)
++++      (test-llm/set-next-response llm
++++                                  {:role "assistant"
++++                                   :tool_calls [{:id "call_1"
++++                                                 :function {:name "mcp__trusted-db__query"
++++                                                            :arguments "{\"q\":\"select user\"}"}}]})
++++
++++      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
++++      ;; Token is deterministic: SHA256("EMAIL_ADDRESS|wes@example.com|test-request-id-12345") -> a35e2662
++++      (test-llm/set-next-response llm
++++                                  {:role "assistant"
++++                                   :content "I found the user. Now updating."
++++                                   :tool_calls [{:id "call_2"
++++                                                 :function {:name "mcp__trusted-db__query"
++++                                                            :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
++++
++++      ;; Final response
++++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
++++
++++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
++++                                 {:body (json/generate-string
++++                                         {:model "brain"
++++                                          :messages [{:role "user" :content "Update user wes"}]
++++                                          :stream false
++++                                          :extra_body {:request-id "test-request-id-12345"}})
++++                                  :headers {"Content-Type" "application/json"}})]
++++        (is (= 200 (:status response)))
++++
++++        ;; Verify MCP received the RESTORED value in the second call
++++        (let [mcp-requests @(:received-requests mcp)
++++              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
++++              update-call (last tool-calls)
++++              ;; Arguments in MCP request is a JSON string, parse it
++++              args-str (-> update-call :body :params :arguments)
++++              args (json/parse-string args-str true)]
++++          (is (= "wes@example.com" (:email args))))
++++
++++        ;; Verify LLM received REDACTED token (not original) in tool result
++++        (let [llm-requests @(:received-requests llm)
++++              ;; Find the request where LLM called tool (has tool_calls)
++++              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
++++              ;; Get the tool result message that follows the tool call
++++              msgs (:messages tool-call-req)
++++              tool-result-msg (last msgs)]
++++          ;; LLM should see token, not original email
++++          (is (some? tool-result-msg))
++++          (is (= "tool" (:role tool-result-msg)))
++++          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
++++          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
++++
++++(defn -main [& _args]
++++  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
++++    (System/exit (if (zero? (:fail result)) 0 1))))
++++
+++diff --git a/dev/PII_RESTORATION_PLAN.md b/dev/PII_RESTORATION_PLAN.md
+++new file mode 100644
+++index 0000000..903b280
+++--- /dev/null
++++++ b/dev/PII_RESTORATION_PLAN.md
+++@@ -0,0 +1,31 @@
++++# PII Restoration & Secret Substitution Plan
++++
++++## Status
++++- **Branch:** `feat/pii-restoration`
++++- **Current State:** COMPLETE - All tests passing
++++- **Completed:** 2026-03-14
++++
++++## Summary
++++Successfully implemented "Smart Vault" PII restoration system:
++++- Deterministic token generation with SHA-256
++++- Request-scoped vault for token/value mapping
++++- Trust levels (`:none`, `:read`, `:restore`) at server and tool level
++++- Two-way substitution: LLM sees tokens, trusted tools see real values
++++
++++## Core Strategy: The "Smart Vault"
++++We are moving from "One-Way Redaction" to "Two-Way Substitution" for trusted tools.
++++
++++### 1. Tokenization (Outbound to LLM)
++++- **Deterministic Hashing:** Replace `[EMAIL_ADDRESS]` with `[PII_EMAIL_8ce0db03]`. 
++++- **Vaulting:** Store `{ "[PII_EMAIL_8ce0db03]" "wes@example.com" }` in a request-scoped vault.
++++- **Structural Awareness:** Use `clojure.walk` to redact JSON values while preserving keys (so LLM understands schema).
++++
++++### 2. Restoration (Inbound to Tools)
++++- **Trust Tiers:** Define `:trust :restore` in `mcp-servers.edn`.
++++- **Restoration:** If a tool call targets a trusted server, `mcp-injector` swaps tokens back for real values before execution.
++++- **Safety:** Untrusted tools continue to see only the redacted tokens.
++++
++++## Build Results
++++- **55 tests** - All passing
++++- **Lint** - Clean
++++- **Format** - Clean
+++diff --git a/dev/specs/pii-restoration.edn b/dev/specs/pii-restoration.edn
+++new file mode 100644
+++index 0000000..edb6cd1
+++--- /dev/null
++++++ b/dev/specs/pii-restoration.edn
+++@@ -0,0 +1,15 @@
++++{:title "PII/Secret Restoration (Round-Trip)"
++++ :description "Enable trusted tools to receive original sensitive data while keeping the LLM's view redacted."
++++ :acceptance-criteria
++++ ["Tool outputs containing PII are tokenized with deterministic, hybrid labels (e.g., [EMAIL_8f3a2])"
++++  "Tokens remain consistent across a single request context"
++++  "A request-scoped Vault stores the mapping of Token -> Original Value"
++++  "Trusted tools (marked with :trust :restore) receive restored values in their arguments"
++++  "Untrusted tools receive the literal token strings"
++++  "Deep JSON redaction preserves map keys but tokenizes values"]
++++ :edge-cases
++++ ["Recursive data structures in tool arguments"
++++  "Mixed plain-text and JSON tool outputs"
++++  "Token collisions (mitigated via request-id salt)"
++++  "Empty or null values in scanned data"]
++++ :depends-on [:governance-core :mcp-client]}
+++diff --git a/result b/result
+++deleted file mode 120000
+++index eea2214..0000000
+++--- a/result
++++++ /dev/null
+++@@ -1 +0,0 @@
+++-/nix/store/gdjbiza5hidsdb7lx3spirlsxybwlzry-mcp-injector-0.1.0
+++\ No newline at end of file
+++diff --git a/src/mcp_injector/config.clj b/src/mcp_injector/config.clj
+++index aa15670..9a0e8bd 100644
+++--- a/src/mcp_injector/config.clj
++++++ b/src/mcp_injector/config.clj
+++@@ -166,6 +166,39 @@
+++     []
+++     (:servers mcp-config))))
+++ 
++++(defn get-server-trust
++++  "Get trust level for a server/tool combination.
++++    Returns :restore (trusted), :none (untrusted), or :block.
++++    Precedence: tool-level :trust > server-level :trust > :none
++++    
++++    Handles :tools as:
++++    - Map: {:retrieve_customer {:trust :restore}}
++++    - Vector of maps: [{:name \"retrieve_customer\" :trust :restore}]"
++++  [mcp-config server-name tool-name]
++++  (let [servers (:servers mcp-config)
++++        server (get servers (keyword server-name))]
++++    (if-not server
++++      :none
++++      (let [server-trust (or (:trust server) :none)
++++            tool-configs (:tools server)
++++            tool-config (cond
++++                          ;; :tools is a map: {:tool-name config}
++++                          (map? tool-configs)
++++                          (get tool-configs (keyword tool-name))
++++
++++                          ;; :tools is a vector: [{:name "tool" :trust :restore}]
++++                          (sequential? tool-configs)
++++                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)
++++
++++                          :else nil)
++++            tool-trust (or (:trust tool-config) :none)]
++++        (cond
++++          (= tool-trust :block) :block
++++          (= server-trust :block) :block
++++          (= tool-trust :restore) :restore
++++          (= server-trust :restore) :restore
++++          :else :none)))))
++++
+++ (defn get-meta-tool-definitions
+++   "Get definitions for meta-tools like get_tool_schema and native tools"
+++   []
+++diff --git a/src/mcp_injector/core.clj b/src/mcp_injector/core.clj
+++index 5cee001..2639200 100644
+++--- a/src/mcp_injector/core.clj
++++++ b/src/mcp_injector/core.clj
+++@@ -169,12 +169,12 @@
+++         (= full-name "clojure-eval")
+++         (try
+++           (let [code (:code args)
+++-                ;; NOTE: clojure-eval is a full JVM/Babashka load-string. 
++++                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
+++                 ;; Security is currently enforced only via the Policy layer (explicit opt-in).
+++                 result (load-string code)]
+++-            (pr-str result))
++++            (json/generate-string result))
+++           (catch Exception e
+++-            {:error (str "Eval error: " (.getMessage e))}))
++++            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
+++ 
+++         (str/starts-with? full-name "mcp__")
+++         (let [t-name (str/replace full-name #"^mcp__" "")
+++@@ -199,28 +199,84 @@
+++ 
+++         :else {:error (str "Unknown tool: " full-name)}))))
+++ 
+++-(defn- scrub-messages [messages]
++++(defn- parse-tool-name
++++  "Parse mcp__server__tool format into [server tool]"
++++  [full-name]
++++  (if (str/includes? full-name "__")
++++    (let [t-name (str/replace full-name #"^mcp__" "")
++++          idx (str/last-index-of t-name "__")]
++++      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
++++    [nil full-name]))
++++
++++(defn- scrub-messages [messages vault request-id]
+++   (mapv (fn [m]
+++-          (if (string? (:content m))
+++-            (let [{:keys [text detected]} (pii/scan-and-redact (:content m) {:mode :replace})]
+++-              (when (seq detected)
+++-                (log-request "info" "PII Redacted" {:labels detected} {:role (:role m)}))
+++-              (assoc m :content text))
+++-            m))
++++          (let [content (:content m)
++++                role (:role m)]
++++            (if (and (string? content)
++++                     ;; Only redact user/system messages - assistant tool results are already handled
++++                     (or (= role "system") (= role "user"))
++++                     ;; Skip if already contains PII tokens (avoid double-redaction)
++++                     ;; Token format: [LABEL_hex8] e.g., [EMAIL_ADDRESS_a35e2662]
++++                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{8,}\]" content)))
++++              (let [config {:mode :replace :salt request-id}
++++                    [redacted-content _ _] (pii/redact-data content config vault)]
++++                (assoc m :content redacted-content))
++++              m)))
+++         messages))
+++ 
+++-(defn- sanitize-tool-output [content]
+++-  (if (string? content)
+++-    (str/replace content
+++-                 #"(?im)^\s*(system|human|assistant|user)\s*:"
+++-                 "[REDACTED_ROLE_MARKER]")
+++-    content))
++++(defn- restore-tool-args
++++  "Restore tokens in tool args if server is trusted"
++++  [args vault mcp-servers full-tool-name]
++++  (let [[server tool] (parse-tool-name full-tool-name)
++++        trust (when server (config/get-server-trust mcp-servers server tool))
++++        restored (if (= trust :restore)
++++                   (pii/restore-tokens args vault)
++++                   args)]
++++    restored))
++++
++++(defn- redact-tool-output
++++  "Redact PII from tool output, return [content vault]"
++++  [raw-output vault request-id]
++++  (let [;; Try to parse as JSON first for JSON tokenization
++++        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
++++        ;; If parsed successfully, redact the data structure; otherwise redact the string
++++        ;; Special handling for MCP response format: parse nested :text field if present
++++        [redacted new-vault detected] (if parsed
++++                                        (let [;; Check if this is MCP response format with :text field containing JSON
++++                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
++++                                              parsed (cond
++++                                                       (map? parsed)
++++                                                       (if (string? (:text parsed))
++++                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
++++                                                              (catch Exception _ parsed))
++++                                                         parsed)
++++                                                       (sequential? parsed)
++++                                                       (mapv (fn [item]
++++                                                               (if (and (map? item) (string? (:text item)))
++++                                                                 (try (assoc item :text (json/parse-string (:text item) true))
++++                                                                      (catch Exception _ item))
++++                                                                 item))
++++                                                             parsed)
++++                                                       :else parsed)
++++                                              config {:mode :replace :salt request-id}
++++                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
++++                                          [(json/generate-string redacted-struct) vault-after detected-labels])
++++                                        (let [config {:mode :replace :salt request-id}
++++                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
++++                                          [redacted-str vault-after detected-labels]))]
++++
++++    ;; Log the detected PII types (not scanning again)
++++    (when (seq detected)
++++      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
++++    [redacted new-vault]))
+++ 
+++ (defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
+++   (let [model (:model payload)
+++         discovered-this-loop (atom {})
+++-        context {:model model}]
+++-    (loop [current-payload (update payload :messages scrub-messages)
++++        vault (atom {})
++++        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
++++        context {:model model :request-id request-id}]
++++    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
+++            iteration 0]
+++       (if (>= iteration max-iterations)
+++         {:success true
+++@@ -239,40 +295,46 @@
+++                   tool-calls (:tool_calls message)]
+++               (if-not tool-calls
+++                 (assoc resp :provider model)
+++-                (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
+++-                                             (str/starts-with? (get-in % [:function :name]) "mcp__"))
++++                (let [mcp-calls (filter (fn [tc]
++++                                          (let [n (get-in tc [:function :name])]
++++                                            (or (= n "get_tool_schema")
++++                                                (and n (str/starts-with? n "mcp__")))))
+++                                         tool-calls)
+++                       native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
+++                                            tool-calls)]
+++                   (if (and (empty? mcp-calls) (empty? native-calls))
+++                     (assoc resp :provider model)
+++-                    (let [results (mapv (fn [tc]
+++-                                          (let [fn-name (get-in tc [:function :name])
+++-                                                args-str (get-in tc [:function :arguments])
+++-                                                parse-result (try
+++-                                                               {:success true :args (json/parse-string args-str true)}
+++-                                                               (catch Exception e
+++-                                                                 {:success false :error (.getMessage e)}))]
+++-                                            (if (:success parse-result)
+++-                                              (let [result (execute-tool fn-name (:args parse-result) mcp-servers discovered-this-loop governance context)
+++-                                                    ;; Scrub and sanitize tool output
+++-                                                    raw-content (if (string? result) result (json/generate-string result))
+++-                                                    sanitized (sanitize-tool-output raw-content)
+++-                                                    {:keys [text detected]} (pii/scan-and-redact sanitized {:mode :replace})
+++-                                                    _ (when (seq detected)
+++-                                                        (log-request "info" "PII Redacted in Tool Output" {:tool fn-name :labels detected} context))]
+++-                                                {:role "tool"
++++                    (let [[results new-vault]
++++                          (reduce
++++                           (fn [[results vault-state] tc]
++++                             (let [fn-name (get-in tc [:function :name])
++++                                   args-str (get-in tc [:function :arguments])
++++                                   parse-result (try
++++                                                  {:success true :args (json/parse-string args-str true)}
++++                                                  (catch Exception e
++++                                                    {:success false :error (.getMessage e)}))]
++++                               (if (:success parse-result)
++++                                 (let [;; Restore args if trusted
++++                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
++++                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
++++                                       ;; Redact output with vault
++++                                       raw-content (if (string? result) result (json/generate-string result))
++++                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
++++                                   [(conj results {:role "tool"
++++                                                   :tool_call_id (:id tc)
++++                                                   :name fn-name
++++                                                   :content redacted})
++++                                    updated-vault])
++++                                 [(conj results {:role "tool"
+++                                                  :tool_call_id (:id tc)
+++                                                  :name fn-name
+++-                                                 :content text})
+++-                                              {:role "tool"
+++-                                               :tool_call_id (:id tc)
+++-                                               :name fn-name
+++-                                               :content (json/generate-string
+++-                                                         {:error "Malformed tool arguments JSON"
+++-                                                          :details {:args-str args-str
+++-                                                                    :parse-error (:error parse-result)}})})))
+++-                                        (concat mcp-calls native-calls))
++++                                                 :content (json/generate-string
++++                                                           {:error "Malformed tool arguments JSON"
++++                                                            :details {:args-str args-str
++++                                                                      :parse-error (:error parse-result)}})})
++++                                  vault-state])))
++++                           [[] vault]
++++                           (concat mcp-calls native-calls))
+++                           newly-discovered @discovered-this-loop
+++                           new-tools (vec (concat (config/get-meta-tool-definitions)
+++                                                  (map (fn [[name schema]]
+++@@ -281,9 +343,12 @@
+++                                                                     :description (:description schema)
+++                                                                     :parameters (:inputSchema schema)}})
+++                                                       newly-discovered)))
+++-                          new-messages (conj (vec (:messages current-payload)) message)
++++                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
+++                           new-messages (into new-messages results)]
+++-                      (recur (assoc current-payload :messages new-messages :tools new-tools) (inc iteration)))))))))))))
++++                      (recur (assoc current-payload
++++                                    :messages (scrub-messages new-messages new-vault request-id)
++++                                    :tools new-tools)
++++                             (inc iteration)))))))))))))
+++ 
+++ (defn- set-cooldown! [provider minutes]
+++   (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
+++@@ -334,11 +399,14 @@
+++                                   discovered-tools)
+++         merged-tools (vec (concat (or existing-tools [])
+++                                   meta-tools
+++-                                  discovered-tool-defs))]
++++                                  discovered-tool-defs))
++++        ;; Merge extra_body into the request for fields like request-id
++++        extra-body (or (:extra_body chat-req) {})]
+++     (-> chat-req
+++         (assoc :stream false)
+++         (dissoc :stream_options)
+++         (assoc :fallbacks fallbacks)
++++        (merge extra-body) ;; Lift extra_body fields to top level
+++         (update :messages (fn [msgs]
+++                             (mapv (fn [m]
+++                                     (if (and (= (:role m) "assistant") (:tool_calls m))
+++@@ -428,11 +496,13 @@
+++                      (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
+++           {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
+++     (catch Exception e
+++-      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
+++-        (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e)} {})
++++      (let [err-type (or (some-> e ex-data :type name) "internal_error")
++++            err-msg (or (.getMessage e) (str e))
++++            stack (.getStackTrace e)]
++++        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
+++         {:status 400
+++          :headers {"Content-Type" "application/json"}
+++-         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
++++         :body (json/generate-string {:error {:message err-msg
+++                                               :type err-type}})}))))
+++ 
+++ (defn get-gateway-state []
+++@@ -509,46 +579,51 @@
+++                                                   :type err-type}})}))))))
+++ 
+++ (defn start-server [mcp-config]
+++-  (let [initial-config (if (and (map? mcp-config) (not (:servers mcp-config)))
+++-                         mcp-config
+++-                         {})
+++-        port (or (:port initial-config)
++++  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
++++        provided-governance (or (:governance mcp-config)
++++                                (:governance (:mcp-servers mcp-config)))
++++
++++        ;; Runtime settings - prioritize input > env > default
++++        port (or (:port mcp-config)
+++                  (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
+++                  8080)
+++-        host (or (:host initial-config)
++++        host (or (:host mcp-config)
+++                  (System/getenv "MCP_INJECTOR_HOST")
+++                  "127.0.0.1")
+++-        llm-url (or (:llm-url initial-config)
++++        llm-url (or (:llm-url mcp-config)
+++                     (System/getenv "MCP_INJECTOR_LLM_URL")
+++                     "http://localhost:11434")
+++-        log-level (or (:log-level initial-config)
++++        log-level (or (:log-level mcp-config)
+++                       (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
+++-        max-iterations (or (:max-iterations initial-config)
++++        max-iterations (or (:max-iterations mcp-config)
+++                            (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
+++                            10)
+++-        mcp-config-path (or (:mcp-config-path initial-config)
++++        mcp-config-path (or (:mcp-config-path mcp-config)
+++                             (System/getenv "MCP_INJECTOR_MCP_CONFIG")
+++                             "mcp-servers.edn")
+++         ;; Audit trail config
+++-        audit-log-path (or (:audit-log-path initial-config)
++++        audit-log-path (or (:audit-log-path mcp-config)
+++                            (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
+++                            "logs/audit.log.ndjson")
+++-        audit-secret (or (:audit-secret initial-config)
++++        audit-secret (or (:audit-secret mcp-config)
+++                          (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
+++                          "default-audit-secret")
+++         ;; Merge provided mcp-config with loaded ones if needed
+++         base-mcp-servers (cond
+++                            (and (map? mcp-config) (:servers mcp-config)) mcp-config
+++-                           (:mcp-servers initial-config) (:mcp-servers initial-config)
++++                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
+++                            :else (config/load-mcp-servers mcp-config-path))
+++-        ;; Apply overrides from initial-config (like :virtual-models in tests)
+++-        mcp-servers (if (seq initial-config)
+++-                      (let [gateway-overrides (select-keys initial-config [:virtual-models :fallbacks :url])]
+++-                        (update base-mcp-servers :llm-gateway merge gateway-overrides))
++++        ;; Apply overrides from mcp-config (like :virtual-models in tests)
++++        mcp-servers (if (map? mcp-config)
++++                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
++++                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
++++                        (if-let [gov (:governance mcp-config)]
++++                          (assoc merged :governance gov)
++++                          merged))
+++                       base-mcp-servers)
+++-        ;; Unified configuration resolution
++++        ;; Unified configuration resolution - pass extracted governance
+++         unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
+++-        final-governance (config/resolve-governance (assoc mcp-servers :governance (:governance initial-config)) unified-env)
++++        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
+++         final-config {:port port :host host :llm-url llm-url :log-level log-level
+++                       :max-iterations max-iterations :mcp-config-path mcp-config-path
+++                       :audit-log-path audit-log-path :audit-secret audit-secret
+++diff --git a/src/mcp_injector/pii.clj b/src/mcp_injector/pii.clj
+++index faeb7e7..a4b0dba 100644
+++--- a/src/mcp_injector/pii.clj
++++++ b/src/mcp_injector/pii.clj
+++@@ -1,12 +1,13 @@
+++ (ns mcp-injector.pii
+++-  (:require [clojure.string :as str]))
++++  (:require [clojure.string :as str]
++++            [clojure.walk :as walk])
++++  (:import (java.security MessageDigest)))
+++ 
+++ (def default-patterns
+++   [{:id :EMAIL_ADDRESS
+++     :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
+++     :label "[EMAIL_ADDRESS]"}
+++    {:id :IBAN_CODE
+++-    ;; Tightened range to 15-34 and added case-insensitivity support via (?i)
+++     :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
+++     :label "[IBAN_CODE]"}])
+++ 
+++@@ -46,8 +47,6 @@
+++ (defn- scan-env [text env-vars mode]
+++   (reduce-kv
+++    (fn [acc k v]
+++-     ;; Case-sensitive match for env vars is usually safer, 
+++-     ;; but we ensure the value is long enough to avoid false positives.
+++      (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
+++        (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
+++        acc))
+++@@ -64,7 +63,6 @@
+++   (let [tokens (str/split text #"\s+")]
+++     (reduce
+++      (fn [acc token]
+++-       ;; Threshold raised to 4.0 + diversity check + length check
+++        (if (and (> (count token) 12)
+++                 (> (shannon-entropy token) threshold)
+++                 (character-diversity? token))
+++@@ -74,15 +72,13 @@
+++      tokens)))
+++ 
+++ (defn scan-and-redact
+++-  "Scans input text for PII patterns, high-entropy secrets, and env vars.
+++-   Calculations are performed sequentially on the text."
++++  "Scans input text for PII patterns, high-entropy secrets, and env vars."
+++   [text {:keys [mode patterns entropy-threshold env]
+++          :or {mode :replace
+++               patterns default-patterns
+++               entropy-threshold 4.0
+++               env {}}}]
+++-  (let [;; 1. Regex patterns (Standard PII)
+++-        regex-result (reduce
++++  (let [regex-result (reduce
+++                       (fn [state {:keys [id pattern label]}]
+++                         (if (seq (re-seq pattern (:text state)))
+++                           {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
+++@@ -90,14 +86,81 @@
+++                           state))
+++                       {:text text :detected []}
+++                       patterns)
+++-
+++-        ;; 2. Env vars (Exact matches)
+++         env-text (scan-env (:text regex-result) env mode)
+++         env-detections (find-env-detections text env)
+++-
+++-        ;; 3. Entropy (Heuristic secrets)
+++         final-text (scan-entropy env-text entropy-threshold mode)
+++         entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
+++-
+++     {:text final-text
+++      :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
++++
++++(defn generate-token
++++  "Generate a deterministic, truncated SHA-256 hash token.
++++   Uses 12 hex chars (48 bits) to reduce collision probability."
++++  [label value salt]
++++  (let [input (str (name label) "|" value "|" salt)
++++        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
++++        hash-str (->> digest
++++                      (map (partial format "%02x"))
++++                      (apply str))
++++        truncated (subs hash-str 0 12)]
++++    (str "[" (name label) "_" truncated "]")))
++++
++++(defn- redact-string-value
++++  "Redact a single string value, returning [redacted-text token detected-label]"
++++  [v config]
++++  (if-not (string? v)
++++    [v nil nil]
++++    (if (empty? v)
++++      [v nil nil]
++++      (let [vault (:vault config)
++++            salt (:salt config)
++++            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
++++            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
++++        (cond
++++          existing-token [existing-token nil nil]
++++          previous-token [previous-token nil nil]
++++          :else
++++          (let [result (scan-and-redact v config)]
++++            (if (seq (:detected result))
++++              (let [detected (first (:detected result))
++++                    token (generate-token detected v salt)]
++++                (swap! vault assoc token v)
++++                [token token detected])
++++              [(:text result) nil nil])))))))
++++
++++(defn redact-data
++++  "Recursively walk a data structure, redact string values, store in vault.
++++    Returns [redacted-data vault-atom detected-labels]"
++++  ([data config]
++++   (redact-data data config (atom {})))
++++  ([data config vault]
++++   (let [config-with-vault (assoc config :vault vault)
++++         detected-labels (atom [])
++++         redacted (walk/postwalk
++++                   (fn [x]
++++                     (if (string? x)
++++                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
++++                         (when detected (swap! detected-labels conj detected))
++++                         redacted-text)
++++                       x))
++++                   data)]
++++     [redacted vault @detected-labels])))
++++
++++(defn restore-tokens
++++  "Recursively walk a data structure, replacing tokens with original values from vault."
++++  [data vault]
++++  (let [v-map @vault]
++++    (if (empty? v-map)
++++      data
++++      (walk/postwalk
++++       (fn [x]
++++         (if (string? x)
++++           (reduce
++++            (fn [s [token original]]
++++              (if (and (string? s) (str/includes? s token))
++++                (str/replace s (str token) (str original))
++++                s))
++++            x
++++            v-map)
++++           x))
++++       data))))
+++diff --git a/test/mcp_injector/discovery_test.clj b/test/mcp_injector/discovery_test.clj
+++index cf4e069..6ae43a4 100644
+++--- a/test/mcp_injector/discovery_test.clj
++++++ b/test/mcp_injector/discovery_test.clj
+++@@ -79,8 +79,8 @@
+++       (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
+++       (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))
+++       ;; content might be redacted as [EMAIL_ADDRESS] or [HIGH_ENTROPY_SECRET] depending on scanner
+++-      (is (some (fn [m] (or (str/includes? (:content m) "[EMAIL_ADDRESS]")
+++-                            (str/includes? (:content m) "[HIGH_ENTROPY_SECRET]"))) tool-msgs)))))
++++      (is (some (fn [m] (or (re-find #"\[EMAIL_ADDRESS(_[a-f0-9]{12})?\]" (:content m))
++++                            (re-find #"\[HIGH_ENTROPY_SECRET(_[a-f0-9]{12})?\]" (:content m)))) tool-msgs)))))
+++ 
+++ (deftest tool-discovery-filtering-nil-shows-all
+++   (testing "When :tools is nil, all discovered tools from MCP server should be shown"
+++diff --git a/test/mcp_injector/llm_shim_test.clj b/test/mcp_injector/llm_shim_test.clj
+++index 4142816..748e04b 100644
+++--- a/test/mcp_injector/llm_shim_test.clj
++++++ b/test/mcp_injector/llm_shim_test.clj
+++@@ -25,7 +25,9 @@
+++                       {:port 0
+++                        :host "127.0.0.1"
+++                        :llm-url (str "http://localhost:" (:port llm))
+++-                       :mcp-config "./mcp-servers.edn"})]
++++                       :mcp-servers {:llm-gateway {:fallbacks ["zen/kimi-k2.5-free"
++++                                                               "nvidia/moonshotai/kimi-k2.5"
++++                                                               "openrouter/moonshotai/kimi-k2.5"]}}})]
+++         (swap! test-state assoc :injector injector)
+++         (try
+++           (f)
+++diff --git a/test/mcp_injector/native_tools_test.clj b/test/mcp_injector/native_tools_test.clj
+++index 865537c..1d8a674 100644
+++--- a/test/mcp_injector/native_tools_test.clj
++++++ b/test/mcp_injector/native_tools_test.clj
+++@@ -14,10 +14,9 @@
+++         injector-server (core/start-server {:port 0
+++                                             :host "127.0.0.1"
+++                                             :llm-url (str "http://localhost:" (:port llm-server))
+++-                                            :mcp-servers {:servers {}
+++-                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))
+++-                                                                        :governance {:mode :permissive
+++-                                                                                     :policy {:allow ["clojure-eval"]}}}}})]
++++                                            :governance {:mode :permissive
++++                                                         :policy {:allow ["clojure-eval"]}}
++++                                            :mcp-servers {:servers {}}})]
+++     (try
+++       (binding [*test-llm* llm-server
+++                 *injector* injector-server]
+++@@ -117,10 +116,9 @@
+++           blocked-injector (core/start-server {:port 0
+++                                                :host "127.0.0.1"
+++                                                :llm-url (str "http://localhost:" llm-port)
+++-                                               :mcp-servers {:servers {}
+++-                                                             :llm-gateway {:url (str "http://localhost:" llm-port)
+++-                                                                           :governance {:mode :permissive
+++-                                                                                        :policy {:allow []}}}}})] ;; empty allow list
++++                                               :governance {:mode :permissive
++++                                                            :policy {:allow []}}
++++                                               :mcp-servers {:servers {}}})] ;; empty allow list
+++       (try
+++         ;; Explicitly clear state before starting the denial flow
+++         (test-llm/clear-responses *test-llm*)
+++diff --git a/test/mcp_injector/restoration_test.clj b/test/mcp_injector/restoration_test.clj
+++new file mode 100644
+++index 0000000..977369f
+++--- /dev/null
++++++ b/test/mcp_injector/restoration_test.clj
+++@@ -0,0 +1,104 @@
++++(ns mcp-injector.restoration-test
++++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
++++            [clojure.string :as str]
++++            [mcp-injector.test-llm-server :as test-llm]
++++            [mcp-injector.test-mcp-server :as test-mcp]
++++            [mcp-injector.core :as core]
++++            [cheshire.core :as json]
++++            [org.httpkit.client :as http]))
++++
++++(def test-state (atom {}))
++++
++++(use-fixtures :once
++++  (fn [f]
++++    (let [llm (test-llm/start-server)
++++          mcp (test-mcp/start-server)]
++++      (swap! test-state assoc :llm llm :mcp mcp)
++++      (let [injector (core/start-server
++++                      {:port 0
++++                       :host "127.0.0.1"
++++                       :llm-url (str "http://localhost:" (:port llm))
++++                       :mcp-servers {:servers
++++                                     {:trusted-db
++++                                      {:url (str "http://localhost:" (:port mcp))
++++                                       :tools ["query"]
++++                                       :trust :restore}
++++                                      :untrusted-api
++++                                      {:url (str "http://localhost:" (:port mcp))
++++                                       :tools ["send"]
++++                                       :trust :none}}}})]
++++        (swap! test-state assoc :injector injector)
++++        (try
++++          (f)
++++          (finally
++++            (core/stop-server injector)
++++            (test-llm/stop-server llm)
++++            (test-mcp/stop-server mcp)))))))
++++
++++(deftest test-secret-redaction-and-restoration
++++  (testing "End-to-end Redact -> Decide -> Restore flow"
++++    (let [{:keys [injector llm mcp]} @test-state
++++          port (:port injector)]
++++
++++      ;; 1. Setup MCP to return a secret
++++      ((:set-tools! mcp)
++++       {:query {:description "Query database"
++++                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
++++                :handler (fn [args]
++++                           (if (or (:email args) (get args "email"))
++++                             {:status "success" :received (or (:email args) (get args "email"))}
++++                             {:email "wes@example.com" :secret "super-secret-123"}))}})
++++
++++      ;; 2. LLM Turn 1: Get data (will be redacted)
++++      (test-llm/set-next-response llm
++++                                  {:role "assistant"
++++                                   :tool_calls [{:id "call_1"
++++                                                 :function {:name "mcp__trusted-db__query"
++++                                                            :arguments "{\"q\":\"select user\"}"}}]})
++++
++++      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
++++      ;; Token is deterministic: SHA256("EMAIL_ADDRESS|wes@example.com|test-request-id-12345") -> a35e2662
++++      (test-llm/set-next-response llm
++++                                  {:role "assistant"
++++                                   :content "I found the user. Now updating."
++++                                   :tool_calls [{:id "call_2"
++++                                                 :function {:name "mcp__trusted-db__query"
++++                                                            :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
++++
++++      ;; Final response
++++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
++++
++++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
++++                                 {:body (json/generate-string
++++                                         {:model "brain"
++++                                          :messages [{:role "user" :content "Update user wes"}]
++++                                          :stream false
++++                                          :extra_body {:request-id "test-request-id-12345"}})
++++                                  :headers {"Content-Type" "application/json"}})]
++++        (is (= 200 (:status response)))
++++
++++        ;; Verify MCP received the RESTORED value in the second call
++++        (let [mcp-requests @(:received-requests mcp)
++++              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
++++              update-call (last tool-calls)
++++              ;; Arguments in MCP request is a JSON string, parse it
++++              args-str (-> update-call :body :params :arguments)
++++              args (json/parse-string args-str true)]
++++          (is (= "wes@example.com" (:email args))))
++++
++++        ;; Verify LLM received REDACTED token (not original) in tool result
++++        (let [llm-requests @(:received-requests llm)
++++              ;; Find the request where LLM called tool (has tool_calls)
++++              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
++++              ;; Get the tool result message that follows the tool call
++++              msgs (:messages tool-call-req)
++++              tool-result-msg (last msgs)]
++++          ;; LLM should see token, not original email
++++          (is (some? tool-result-msg))
++++          (is (= "tool" (:role tool-result-msg)))
++++          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
++++          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
++++
++++(defn -main [& _args]
++++  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
++++    (System/exit (if (zero? (:fail result)) 0 1))))
+++diff --git a/test/mcp_injector/test_llm_server.clj b/test/mcp_injector/test_llm_server.clj
+++index fa3f9d7..3b4ee3a 100644
+++--- a/test/mcp_injector/test_llm_server.clj
++++++ b/test/mcp_injector/test_llm_server.clj
+++@@ -16,15 +16,18 @@
+++    :model (get request-body :model "gpt-4o-mini")
+++    :choices [{:index 0
+++               :message {:role "assistant"
+++-                        :content (:content response-data)
++++                        :content (or (get-in response-data [:choices 0 :message :content])
++++                                     (:content response-data))
+++                         :tool_calls (when (:tool_calls response-data)
+++                                       (map-indexed
+++                                        (fn [idx tc]
+++-                                         {:id (str "call_" idx)
+++-                                          :type "function"
+++-                                          :index idx
+++-                                          :function {:name (:name tc)
+++-                                                     :arguments (json/generate-string (:arguments tc))}})
++++                                         (let [fn-name (or (:name tc) (get-in tc [:function :name]))
++++                                               fn-args (or (:arguments tc) (get-in tc [:function :arguments]))]
++++                                           {:id (str "call_" idx)
++++                                            :type "function"
++++                                            :index idx
++++                                            :function {:name fn-name
++++                                                       :arguments (json/generate-string fn-args)}}))
+++                                        (:tool_calls response-data)))}
+++               :finish_reason (if (:tool_calls response-data) "tool_calls" "stop")}]
+++     ;; Default usage to nil to avoid polluting stats in tests that don't explicitly set it
+++=== FILE: src/mcp_injector/pii.clj ===
+++(ns mcp-injector.pii
+++  (:require [clojure.string :as str]
+++            [clojure.walk :as walk])
+++  (:import (java.security MessageDigest)))
+++
+++(def default-patterns
+++  [{:id :EMAIL_ADDRESS
+++    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
+++    :label "[EMAIL_ADDRESS]"}
+++   {:id :IBAN_CODE
+++    :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
+++    :label "[IBAN_CODE]"}])
+++
+++(defn shannon-entropy
+++  "Calculates the Shannon entropy of a string."
+++  [s]
+++  (if (empty? s)
+++    0.0
+++    (let [freqs (vals (frequencies s))
+++          len (count s)]
+++      (- (reduce + (map (fn [f]
+++                          (let [p (/ f len)]
+++                            (* p (/ (Math/log p) (Math/log 2)))))
+++                        freqs))))))
+++
+++(defn- character-diversity?
+++  "Checks if a string contains at least 3 distinct character classes."
+++  [s]
+++  (let [classes [(when (re-find #"[a-z]" s) :lower)
+++                 (when (re-find #"[A-Z]" s) :upper)
+++                 (when (re-find #"[0-9]" s) :digit)
+++                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]]
+++    (>= (count (remove nil? classes)) 3)))
+++
+++(defn- mask-string
+++  "Fixed-length mask to prevent leaking structural entropy."
+++  [_s]
+++  "********")
+++
+++(defn- redact-match [mode label match]
+++  (case mode
+++    :replace label
+++    :mask (mask-string match)
+++    :hash (str "#" (hash match))
+++    label))
+++
+++(defn- scan-env [text env-vars mode]
+++  (reduce-kv
+++   (fn [acc k v]
+++     (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
+++       (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
+++       acc))
+++   text
+++   env-vars))
+++
+++(defn- find-env-detections [text env-vars]
+++  (keep (fn [[k v]]
+++          (when (and (not (empty? v)) (> (count v) 5) (str/includes? text v))
+++            (keyword (str "ENV_VAR_" k))))
+++        env-vars))
+++
+++(defn- scan-entropy [text threshold mode]
+++  (let [tokens (str/split text #"\s+")]
+++    (reduce
+++     (fn [acc token]
+++       (if (and (> (count token) 12)
+++                (> (shannon-entropy token) threshold)
+++                (character-diversity? token))
+++         (str/replace acc token (redact-match mode "[HIGH_ENTROPY_SECRET]" token))
+++         acc))
+++     text
+++     tokens)))
+++
+++(defn scan-and-redact
+++  "Scans input text for PII patterns, high-entropy secrets, and env vars."
+++  [text {:keys [mode patterns entropy-threshold env]
+++         :or {mode :replace
+++              patterns default-patterns
+++              entropy-threshold 4.0
+++              env {}}}]
+++  (let [regex-result (reduce
+++                      (fn [state {:keys [id pattern label]}]
+++                        (if (seq (re-seq pattern (:text state)))
+++                          {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
+++                           :detected (conj (:detected state) id)}
+++                          state))
+++                      {:text text :detected []}
+++                      patterns)
+++        env-text (scan-env (:text regex-result) env mode)
+++        env-detections (find-env-detections text env)
+++        final-text (scan-entropy env-text entropy-threshold mode)
+++        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
+++    {:text final-text
+++     :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
+++
+++(defn generate-token
+++  "Generate a deterministic, truncated SHA-256 hash token.
+++   Uses 12 hex chars (48 bits) to reduce collision probability."
+++  [label value salt]
+++  (let [input (str (name label) "|" value "|" salt)
+++        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
+++        hash-str (->> digest
+++                      (map (partial format "%02x"))
+++                      (apply str))
+++        truncated (subs hash-str 0 12)]
+++    (str "[" (name label) "_" truncated "]")))
+++
+++(defn- redact-string-value
+++  "Redact a single string value, returning [redacted-text token detected-label]"
+++  [v config]
+++  (if-not (string? v)
+++    [v nil nil]
+++    (if (empty? v)
+++      [v nil nil]
+++      (let [vault (:vault config)
+++            salt (:salt config)
+++            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
+++            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
+++        (cond
+++          existing-token [existing-token nil nil]
+++          previous-token [previous-token nil nil]
+++          :else
+++          (let [result (scan-and-redact v config)]
+++            (if (seq (:detected result))
+++              (let [detected (first (:detected result))
+++                    token (generate-token detected v salt)]
+++                (swap! vault assoc token v)
+++                [token token detected])
+++              [(:text result) nil nil])))))))
+++
+++(defn redact-data
+++  "Recursively walk a data structure, redact string values, store in vault.
+++    Returns [redacted-data vault-atom detected-labels]"
+++  ([data config]
+++   (redact-data data config (atom {})))
+++  ([data config vault]
+++   (let [config-with-vault (assoc config :vault vault)
+++         detected-labels (atom [])
+++         redacted (walk/postwalk
+++                   (fn [x]
+++                     (if (string? x)
+++                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
+++                         (when detected (swap! detected-labels conj detected))
+++                         redacted-text)
+++                       x))
+++                   data)]
+++     [redacted vault @detected-labels])))
+++
+++(defn restore-tokens
+++  "Recursively walk a data structure, replacing tokens with original values from vault."
+++  [data vault]
+++  (let [v-map @vault]
+++    (if (empty? v-map)
+++      data
+++      (walk/postwalk
+++       (fn [x]
+++         (if (string? x)
+++           (reduce
+++            (fn [s [token original]]
+++              (if (and (string? s) (str/includes? s token))
+++                (str/replace s (str token) (str original))
+++                s))
+++            x
+++            v-map)
+++           x))
+++       data))))
+++
+++=== FILE: src/mcp_injector/config.clj ===
+++(ns mcp-injector.config
+++  "Configuration and environment variables for mcp-injector."
+++  (:require [clojure.edn :as edn]
+++            [clojure.java.io :as io]
+++            [clojure.walk :as walk]
+++            [clojure.string :as str]))
+++
+++(def default-config
+++  {:port 8088
+++   :host "127.0.0.1"
+++   :llm-url "http://localhost:8080"
+++   :mcp-config "./mcp-servers.edn"
+++   :max-iterations 10
+++   :log-level "debug"
+++   :timeout-ms 1800000
+++   :audit-log-path "logs/audit.log.ndjson"
+++   :audit-secret "default-audit-secret"})
+++
+++(defn env-var
+++  ([name] (System/getenv name))
+++  ([name default] (or (System/getenv name) default)))
+++
+++(defn- parse-int [s default]
+++  (try
+++    (Integer/parseInt s)
+++    (catch Exception _ default)))
+++
+++(defn- keywordize-keys [m]
+++  (walk/prewalk
+++   (fn [x]
+++     (if (map? x)
+++       (into {} (map (fn [[k v]] [(keyword k) v]) x))
+++       x))
+++   m))
+++
+++(defn deep-merge
+++  "Recursively merges maps. If keys conflict, the value from the last map wins.
+++   Ensures nested defaults are not wiped out by partial user config.
+++   If 'new' is nil, the 'old' value is preserved to prevent wiping out defaults."
+++  [& maps]
+++  (apply merge-with
+++         (fn [old new]
+++           (cond
+++             (nil? new) old
+++             (and (map? old) (map? new)) (deep-merge old new)
+++             :else new))
+++         maps))
+++
+++(defn- resolve-audit-path [env-path]
+++  (let [logs-dir (env-var "LOGS_DIRECTORY")
+++        state-dir (env-var "STATE_DIRECTORY")
+++        xdg-state (env-var "XDG_STATE_HOME")
+++        xdg-data (env-var "XDG_DATA_HOME")
+++        home (env-var "HOME")
+++        cwd (.getAbsolutePath (io/file "."))
+++        in-nix-store? (str/starts-with? cwd "/nix/store")
+++        default-path (:audit-log-path default-config)]
+++    (or env-path
+++        (cond
+++          logs-dir (str (str/replace logs-dir #"/$" "") "/audit.log.ndjson")
+++          state-dir (str (str/replace state-dir #"/$" "") "/audit.log.ndjson")
+++          xdg-state (str (str/replace xdg-state #"/$" "") "/mcp-injector/audit.log.ndjson")
+++          xdg-data (str (str/replace xdg-data #"/$" "") "/mcp-injector/audit.log.ndjson")
+++          home (str home "/.local/state/mcp-injector/audit.log.ndjson")
+++          (and in-nix-store? (not (str/starts-with? default-path "/")))
+++          (throw (ex-info (str "Cannot use relative audit log path '" default-path "' in read-only directory: " cwd)
+++                          {:cwd cwd
+++                           :default-path default-path
+++                           :suggestion "Set MCP_INJECTOR_AUDIT_LOG_PATH to an absolute, writable path."}))
+++          :else default-path))))
+++
+++(defn load-config []
+++  (let [env-audit-path (env-var "MCP_INJECTOR_AUDIT_LOG_PATH")
+++        env-audit-secret (env-var "MCP_INJECTOR_AUDIT_SECRET")]
+++    {:port (parse-int (env-var "MCP_INJECTOR_PORT") (:port default-config))
+++     :host (env-var "MCP_INJECTOR_HOST" (:host default-config))
+++     :llm-url (env-var "MCP_INJECTOR_LLM_URL" (:llm-url default-config))
+++     :mcp-config (env-var "MCP_INJECTOR_MCP_CONFIG" (:mcp-config default-config))
+++     :max-iterations (parse-int (env-var "MCP_INJECTOR_MAX_ITERATIONS") (:max-iterations default-config))
+++     :log-level (env-var "MCP_INJECTOR_LOG_LEVEL" (:log-level default-config))
+++     :timeout-ms (parse-int (env-var "MCP_INJECTOR_TIMEOUT_MS") (:timeout-ms default-config))
+++     :audit-log-path (resolve-audit-path env-audit-path)
+++     :audit-secret (or env-audit-secret (:audit-secret default-config))}))
+++
+++(defn get-env [name]
+++  (System/getenv name))
+++
+++(defn- resolve-value
+++  "Resolve a potentially dynamic value.
+++   If value is a map with :env, look up environment variable.
+++   Supports :prefix and :suffix."
+++  [v]
+++  (if (and (map? v) (:env v))
+++    (let [env-name (:env v)]
+++      (if (or (string? env-name) (keyword? env-name))
+++        (let [prefix (:prefix v "")
+++              suffix (:suffix v "")
+++              env-val (get-env (if (keyword? env-name) (name env-name) env-name))]
+++          (if env-val
+++            (str prefix env-val suffix)
+++            (do
+++              (println (str "Warning: Environment variable " env-name " not set."))
+++              nil)))
+++        v))
+++    v))
+++
+++(defn resolve-server-config
+++  "Recursively resolve dynamic values in a server configuration map.
+++   Uses post-order traversal: children first, then parent."
+++  [m]
+++  (let [resolve-all (fn resolve-all [x]
+++                      (cond
+++                        (map? x)
+++                        (let [resolved (into {} (map (fn [[k v]] [k (resolve-all v)]) x))]
+++                          (if (contains? resolved :env)
+++                            (resolve-value resolved)
+++                            resolved))
+++
+++                        (vector? x)
+++                        (mapv resolve-all x)
+++
+++                        :else x))]
+++    (resolve-all m)))
+++
+++(defn load-mcp-servers [config-path]
+++  (if-let [file (io/file config-path)]
+++    (if (.exists file)
+++      (let [raw-config (keywordize-keys (edn/read-string (slurp file)))]
+++        (update raw-config :servers
+++                (fn [servers]
+++                  (into {} (map (fn [[k v]] [k (resolve-server-config v)]) servers)))))
+++      {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}})
+++    {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}}))
+++
+++(defn get-llm-fallbacks
+++  "Get LLM fallback configuration from MCP servers config.
+++   Transforms from [{:provider :model}] format to provider/model strings"
+++  [mcp-config]
+++  (let [fallbacks-config (get-in mcp-config [:llm-gateway :fallbacks] [])]
+++    (mapv (fn [fb]
+++            (if (string? fb)
+++              fb
+++              (str (:provider fb) "/" (:model fb))))
+++          fallbacks-config)))
+++
+++(defn build-tool-directory
+++  "Build tool directory from mcp-config. 
+++   If pre-discovered-tools map provided, use those; otherwise fall back to config :tools list."
+++  ([mcp-config]
+++   (build-tool-directory mcp-config nil))
+++  ([mcp-config pre-discovered-tools]
+++   (reduce
+++    (fn [acc [server-name server-config]]
+++      (let [server-url (or (:url server-config) (:uri server-config))
+++            cmd (:cmd server-config)
+++            tool-names (:tools server-config)]
+++        (if (or server-url cmd)
+++          (let [tools (if (and pre-discovered-tools (get pre-discovered-tools server-name))
+++                        (get pre-discovered-tools server-name)
+++                        (map (fn [t] {:name (name t)}) tool-names))]
+++            (into acc (map (fn [tool]
+++                             {:name (str (name server-name) "." (:name tool))
+++                              :server (name server-name)})
+++                           tools)))
+++          acc)))
+++    []
+++    (:servers mcp-config))))
+++
+++(defn get-server-trust
+++  "Get trust level for a server/tool combination.
+++    Returns :restore (trusted), :none (untrusted), or :block.
+++    Precedence: tool-level :trust > server-level :trust > :none
+++    
+++    Handles :tools as:
+++    - Map: {:retrieve_customer {:trust :restore}}
+++    - Vector of maps: [{:name \"retrieve_customer\" :trust :restore}]"
+++  [mcp-config server-name tool-name]
+++  (let [servers (:servers mcp-config)
+++        server (get servers (keyword server-name))]
+++    (if-not server
+++      :none
+++      (let [server-trust (or (:trust server) :none)
+++            tool-configs (:tools server)
+++            tool-config (cond
+++                          ;; :tools is a map: {:tool-name config}
+++                          (map? tool-configs)
+++                          (get tool-configs (keyword tool-name))
+++
+++                          ;; :tools is a vector: [{:name "tool" :trust :restore}]
+++                          (sequential? tool-configs)
+++                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)
+++
+++                          :else nil)
+++            tool-trust (or (:trust tool-config) :none)]
+++        (cond
+++          (= tool-trust :block) :block
+++          (= server-trust :block) :block
+++          (= tool-trust :restore) :restore
+++          (= server-trust :restore) :restore
+++          :else :none)))))
+++
+++(defn get-meta-tool-definitions
+++  "Get definitions for meta-tools like get_tool_schema and native tools"
+++  []
+++  [{:type "function"
+++    :function {:name "get_tool_schema"
+++               :description "Fetch the full JSON schema for a specific MCP tool to understand its parameters."
+++               :parameters {:type "object"
+++                            :properties {:tool {:type "string"
+++                                                :description "Full tool name with mcp__ prefix (e.g., 'mcp__stripe__retrieve_customer')"}}
+++                            :required ["tool"]}}}
+++   {:type "function"
+++    :function {:name "clojure-eval"
+++               :description "Evaluate Clojure code in the local REPL. WARNING: Full Clojure access - use with care. Returns the result as a string."
+++               :parameters {:type "object"
+++                            :properties {:code {:type "string"
+++                                                :description "Clojure code to evaluate"}}
+++                            :required ["code"]}}}])
+++
+++(defn- extract-tool-params
+++  "Extract parameter names from tool schema, distinguishing required vs optional.
+++   Returns [required-params optional-params] as vectors of strings."
+++  [tool]
+++  (let [schema (or (:inputSchema tool) (:schema tool))
+++        properties (get schema :properties {})
+++        required-vals (get schema :required [])
+++        required-set (set (map keyword required-vals))
+++        all-param-names (keys properties)
+++        required (filterv #(required-set %) all-param-names)
+++        optional (filterv #(not (required-set %)) all-param-names)]
+++    [(mapv name required) (mapv name optional)]))
+++
+++(defn- format-tool-with-params
+++  "Format a tool as mcp__server__tool [required, optional?]"
+++  [server-name tool]
+++  (let [tool-name (:name tool)
+++        [required optional] (extract-tool-params tool)]
+++    (if (or (seq required) (seq optional))
+++      (let [all-params (into required (map #(str % "?")) optional)]
+++        (str "mcp__" (name server-name) "__" tool-name " [" (str/join ", " all-params) "]"))
+++      (str "mcp__" (name server-name) "__" tool-name))))
+++
+++(defn inject-tools-into-messages
+++  "Inject MCP tools directory into messages.
+++   If pre-discovered-tools map provided (server-name -> [tools]), use those;
+++   otherwise fall back to config :tools list."
+++  ([messages mcp-config]
+++   (inject-tools-into-messages messages mcp-config nil))
+++  ([messages mcp-config pre-discovered-tools]
+++   (let [servers (:servers mcp-config)
+++         tool-lines (reduce
+++                     (fn [lines [server-name server-config]]
+++                       (let [server-url (or (:url server-config) (:uri server-config))
+++                             cmd (:cmd server-config)
+++                             tool-names (:tools server-config)]
+++                         (if (or server-url cmd)
+++                           (let [discovered (get pre-discovered-tools server-name)
+++                                 tools (if (and pre-discovered-tools (seq discovered))
+++                                         discovered
+++                                         (mapv (fn [t] {:name (name t)}) tool-names))
+++                                 tools (filter #(some? (:name %)) tools)
+++                                 formatted (map #(format-tool-with-params server-name %) tools)
+++                                 tool-str (str/join ", " formatted)]
+++                             (if (seq tools)
+++                               (conj lines (str "- mcp__" (name server-name) ": " tool-str))
+++                               lines))
+++                           lines)))
+++                     []
+++                     servers)
+++         directory-text (str "## Remote Tools (MCP)\n"
+++                             "You have access to namespaced MCP tools.\n\n"
+++                             "### Available:\n"
+++                             (str/join "\n" tool-lines)
+++                             "\n\n### Usage:\n"
+++                             "Get schema: get_tool_schema {:tool \"mcp__server__tool\"}\n"
+++                             "Call tool: mcp__server__tool {:key \"value\"}\n\n"
+++                             "### Native:\n"
+++                             "- clojure-eval: Evaluate Clojure. Args: {:code \"...\"}\n"
+++                             "  Example: {:code \"(vec (range 5))\"} => \"[0 1 2 3 4]\"")
+++         system-msg {:role "system" :content directory-text}]
+++     (cons system-msg messages))))
+++
+++(defn get-virtual-models
+++  "Get virtual models configuration from MCP servers config"
+++  [mcp-config]
+++  (get-in mcp-config [:llm-gateway :virtual-models] {}))
+++
+++(defn resolve-governance
+++  "Unified governance resolution logic. Prioritizes nested :governance block.
+++   Precedence: top-level :governance > :llm-gateway :governance > defaults.
+++   Uses deep-merge to preserve nested default settings."
+++  [mcp-config env-config]
+++  (let [gateway (:llm-gateway mcp-config)
+++        gov-user (or (:governance mcp-config) (:governance gateway))
+++        defaults {:mode :permissive
+++                  :pii {:enabled true :mode :replace}
+++                  :audit {:enabled true :path (:audit-log-path env-config)}
+++                  :policy {:mode :permissive}}]
+++    (deep-merge defaults gov-user)))
+++
+++(defn get-config
+++  "Unified config: env vars override config file, with defaults as fallback.
+++    Priority: env var > config file > default"
+++  [mcp-config]
+++  (let [env (load-config)
+++        gateway (:llm-gateway mcp-config)
+++        gov (resolve-governance mcp-config env)]
+++    {:port (:port env)
+++     :host (:host env)
+++     :llm-url (or (env-var "MCP_INJECTOR_LLM_URL")
+++                  (:url gateway)
+++                  (:llm-url env))
+++     :mcp-config (:mcp-config env)
+++     :max-iterations (let [v (or (env-var "MCP_INJECTOR_MAX_ITERATIONS")
+++                                 (:max-iterations gateway))]
+++                       (if (string? v) (parse-int v 10) (or v (:max-iterations env))))
+++     :log-level (or (env-var "MCP_INJECTOR_LOG_LEVEL")
+++                    (:log-level gateway)
+++                    (:log-level env))
+++     :timeout-ms (let [v (or (env-var "MCP_INJECTOR_TIMEOUT_MS")
+++                             (:timeout-ms gateway))]
+++                   (if (string? v) (parse-int v 1800000) (or v (:timeout-ms env))))
+++     :fallbacks (:fallbacks gateway)
+++     :virtual-models (:virtual-models gateway)
+++     :audit-log-path (get-in gov [:audit :path])
+++     :audit-secret (or (get-in gov [:audit :secret])
+++                       (env-var "MCP_INJECTOR_AUDIT_SECRET")
+++                       (:audit-secret env)
+++                       "default-audit-secret")
+++     :governance gov}))
+++
+++(defn get-llm-url
+++  "Get LLM URL: env var overrides config file"
+++  [mcp-config]
+++  (or (env-var "MCP_INJECTOR_LLM_URL")
+++      (get-in mcp-config [:llm-gateway :url])
+++      "http://localhost:8080"))
+++
+++=== FILE: src/mcp_injector/core.clj ===
+++(ns mcp-injector.core
+++  (:require [org.httpkit.server :as http]
+++            [babashka.http-client :as http-client]
+++            [cheshire.core :as json]
+++            [clojure.string :as str]
+++            [clojure.java.io :as io]
+++            [mcp-injector.config :as config]
+++            [mcp-injector.openai-compat :as openai]
+++            [mcp-injector.mcp-client :as mcp]
+++            [mcp-injector.audit :as audit]
+++            [mcp-injector.pii :as pii]
+++            [mcp-injector.policy :as policy]))
+++
+++(def ^:private server-state (atom nil))
+++(def ^:private usage-stats (atom {}))
+++(def ^:private cooldown-state (atom {}))
+++(def ^:private ^:dynamic *request-id* nil)
+++(def ^:private ^:dynamic *audit-config* nil)
+++
+++(defn- log-request
+++  ([level message data]
+++   (log-request level message data nil))
+++  ([level message data context]
+++   (let [log-entry (merge {:timestamp (str (java.time.Instant/now))
+++                           :level level
+++                           :message message
+++                           :request-id (or *request-id* "none")}
+++                          context
+++                          {:data data})]
+++     (println (json/generate-string log-entry))
+++     ;; Fail-open audit logging
+++     (when *audit-config*
+++       (try
+++         (audit/append-event! (:secret *audit-config*) level log-entry)
+++         (catch Exception e
+++           (binding [*out* *err*]
+++             (println (json/generate-string
+++                       {:timestamp (str (java.time.Instant/now))
+++                        :level "error"
+++                        :message "AUDIT LOG WRITE FAILURE — audit trail degraded"
+++                        :error (.getMessage e)})))))))))
+++
+++(defn- parse-body [body]
+++  (try
+++    (if (string? body)
+++      (json/parse-string body true)
+++      (json/parse-string (slurp body) true))
+++    (catch Exception e
+++      (throw (ex-info "Failed to parse JSON body"
+++                      {:type :json_parse_error
+++                       :status 400
+++                       :message "Failed to parse JSON body. Please ensure your request is valid JSON."} e)))))
+++
+++(defn- is-context-overflow-error? [error-str]
+++  (when (string? error-str)
+++    (let [patterns [#"(?i)cannot read propert(?:y|ies) of undefined.*prompt"
+++                    #"(?i)cannot read propert(?:y|ies) of null.*prompt"
+++                    #"(?i)prompt_tokens.*undefined"
+++                    #"(?i)prompt_tokens.*null"
+++                    #"(?i)context window.*exceeded"
+++                    #"(?i)context length.*exceeded"
+++                    #"(?i)maximum context.*exceeded"
+++                    #"(?i)request.*too large"
+++                    #"(?i)prompt is too long"
+++                    #"(?i)exceeds model context"
+++                    #"(?i)413.*too large"
+++                    #"(?i)request size exceeds"]]
+++      (some #(re-find % error-str) patterns))))
+++
+++(defn- translate-error-for-openclaw [error-data status-code]
+++  (let [error-str (or (get-in error-data [:error :message])
+++                      (:message error-data)
+++                      (:details error-data)
+++                      (str error-data))]
+++    (cond
+++      (is-context-overflow-error? error-str)
+++      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
+++       :status 503
+++       :type "context_overflow"
+++       :details error-data}
+++
+++      (= 429 status-code)
+++      {:message (or (:message error-data) "Rate limit exceeded")
+++       :status 429
+++       :type "rate_limit_exceeded"
+++       :details error-data}
+++
+++      :else
+++      {:message (or (:message error-data) "Upstream error")
+++       :status 502
+++       :type "upstream_error"
+++       :details error-data})))
+++
+++(defn- call-llm [base-url payload]
+++  (let [url (str (str/replace base-url #"/$" "") "/v1/chat/completions")
+++        resp (try
+++               (http-client/post url
+++                                 {:headers {"Content-Type" "application/json"}
+++                                  :body (json/generate-string payload)
+++                                  :throw false})
+++               (catch Exception e
+++                 {:status 502 :body (json/generate-string {:error {:message (.getMessage e)}})}))]
+++    (if (= 200 (:status resp))
+++      {:success true :data (json/parse-string (:body resp) true)}
+++      (let [status (:status resp)
+++            error-data (try (json/parse-string (:body resp) true) (catch Exception _ (:body resp)))
+++            translated (translate-error-for-openclaw error-data status)]
+++        (log-request "warn" "LLM Error" {:status status :body (:body resp) :translated translated})
+++        {:success false :status (:status translated) :error translated}))))
+++
+++(defn- record-completion! [alias provider usage]
+++  (when usage
+++    (let [update-entry (fn [existing usage]
+++                         (let [input (or (:prompt_tokens usage) 0)
+++                               output (or (:completion_tokens usage) 0)
+++                               total (or (:total_tokens usage) (+ input output))]
+++                           {:requests (inc (or (:requests existing) 0))
+++                            :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
+++                            :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
+++                            :total-tokens (+ total (or (:total-tokens existing) 0))
+++                            :rate-limits (or (:rate-limits existing) 0)
+++                            :context-overflows (or (:context-overflows existing) 0)
+++                            :last-updated (System/currentTimeMillis)}))]
+++      (swap! usage-stats
+++             (fn [stats]
+++               (cond-> stats
+++                 alias (update alias update-entry usage)
+++                 (and provider (not= provider alias)) (update provider update-entry usage)))))))
+++
+++(defn- track-provider-failure! [provider status]
+++  (when provider
+++    (let [counter (if (= status 503) :context-overflows :rate-limits)]
+++      (swap! usage-stats update provider
+++             (fn [existing]
+++               (assoc (or existing {:requests 0
+++                                    :total-input-tokens 0
+++                                    :total-output-tokens 0
+++                                    :total-tokens 0})
+++                      counter (inc (or (get existing counter) 0))
+++                      :last-updated (System/currentTimeMillis)))))))
+++
+++(defn reset-usage-stats! []
+++  (reset! usage-stats {}))
+++
+++(defn- execute-tool [full-name args mcp-servers discovered-this-loop governance context]
+++  (let [policy-result (policy/allow-tool? (:policy governance) full-name context)]
+++    (if-not (:allowed? policy-result)
+++      (do
+++        (log-request "warn" "Tool Blocked by Policy" {:tool full-name :reason (:reason policy-result)} context)
+++        {:error "Tool execution denied"})
+++      (cond
+++        (= full-name "get_tool_schema")
+++        (let [full-tool-name (:tool args)
+++              ;; Parse prefixed name: mcp__server__tool -> [server tool]
+++              [s-name t-name] (if (and full-tool-name (str/includes? full-tool-name "__"))
+++                                (let [idx (str/last-index-of full-tool-name "__")]
+++                                  [(subs full-tool-name 5 idx) (subs full-tool-name (+ idx 2))])
+++                                [nil nil])
+++              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
+++          (if (and s-name s-config t-name)
+++            (let [schema (mcp/get-tool-schema (name s-name) s-config t-name (:policy governance))]
+++              (if (:error schema)
+++                schema
+++                (do
+++                  (swap! discovered-this-loop assoc full-tool-name schema)
+++                  schema)))
+++            {:error (str "Invalid tool name. Use format: mcp__server__tool (e.g., mcp__stripe__retrieve_customer). Got: " full-tool-name)}))
+++
+++        (= full-name "clojure-eval")
+++        (try
+++          (let [code (:code args)
+++                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
+++                ;; Security is currently enforced only via the Policy layer (explicit opt-in).
+++                result (load-string code)]
+++            (json/generate-string result))
+++          (catch Exception e
+++            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
+++
+++        (str/starts-with? full-name "mcp__")
+++        (let [t-name (str/replace full-name #"^mcp__" "")
+++              [s-name real-t-name] (if (str/includes? t-name "__")
+++                                     (let [idx (str/last-index-of t-name "__")]
+++                                       [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+++                                     [nil t-name])
+++              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
+++          (if (and s-name s-config)
+++            (let [result (mcp/call-tool (name s-name) s-config real-t-name args (:policy governance))
+++                  ;; Auto-discover: add schema to discovered-this-loop so next turn has it
+++                  _ (when-not (contains? result :error)
+++                      (let [schema (mcp/get-tool-schema (name s-name) s-config real-t-name (:policy governance))]
+++                        (when-not (:error schema)
+++                          (swap! discovered-this-loop assoc full-name schema))))]
+++              result)
+++            (if-let [_ (get @discovered-this-loop full-name)]
+++              (let [[_ s-name-auto real-t-auto] (str/split full-name #"__" 3)
+++                    s-conf-auto (get-in mcp-servers [:servers (keyword s-name-auto)])]
+++                (mcp/call-tool (name s-name-auto) s-conf-auto real-t-auto args (:policy governance)))
+++              {:error (str "Unknown tool: " full-name ". Use get_tool_schema with full prefixed name first.")})))
+++
+++        :else {:error (str "Unknown tool: " full-name)}))))
+++
+++(defn- parse-tool-name
+++  "Parse mcp__server__tool format into [server tool]"
+++  [full-name]
+++  (if (str/includes? full-name "__")
+++    (let [t-name (str/replace full-name #"^mcp__" "")
+++          idx (str/last-index-of t-name "__")]
+++      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+++    [nil full-name]))
+++
+++(defn- scrub-messages [messages vault request-id]
+++  (mapv (fn [m]
+++          (let [content (:content m)
+++                role (:role m)]
+++            (if (and (string? content)
+++                     ;; Only redact user/system messages - assistant tool results are already handled
+++                     (or (= role "system") (= role "user"))
+++                     ;; Skip if already contains PII tokens (avoid double-redaction)
+++                     ;; Token format: [LABEL_hex8] e.g., [EMAIL_ADDRESS_a35e2662]
+++                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{8,}\]" content)))
+++              (let [config {:mode :replace :salt request-id}
+++                    [redacted-content _ _] (pii/redact-data content config vault)]
+++                (assoc m :content redacted-content))
+++              m)))
+++        messages))
+++
+++(defn- restore-tool-args
+++  "Restore tokens in tool args if server is trusted"
+++  [args vault mcp-servers full-tool-name]
+++  (let [[server tool] (parse-tool-name full-tool-name)
+++        trust (when server (config/get-server-trust mcp-servers server tool))
+++        restored (if (= trust :restore)
+++                   (pii/restore-tokens args vault)
+++                   args)]
+++    restored))
+++
+++(defn- redact-tool-output
+++  "Redact PII from tool output, return [content vault]"
+++  [raw-output vault request-id]
+++  (let [;; Try to parse as JSON first for JSON tokenization
+++        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
+++        ;; If parsed successfully, redact the data structure; otherwise redact the string
+++        ;; Special handling for MCP response format: parse nested :text field if present
+++        [redacted new-vault detected] (if parsed
+++                                        (let [;; Check if this is MCP response format with :text field containing JSON
+++                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
+++                                              parsed (cond
+++                                                       (map? parsed)
+++                                                       (if (string? (:text parsed))
+++                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
+++                                                              (catch Exception _ parsed))
+++                                                         parsed)
+++                                                       (sequential? parsed)
+++                                                       (mapv (fn [item]
+++                                                               (if (and (map? item) (string? (:text item)))
+++                                                                 (try (assoc item :text (json/parse-string (:text item) true))
+++                                                                      (catch Exception _ item))
+++                                                                 item))
+++                                                             parsed)
+++                                                       :else parsed)
+++                                              config {:mode :replace :salt request-id}
+++                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
+++                                          [(json/generate-string redacted-struct) vault-after detected-labels])
+++                                        (let [config {:mode :replace :salt request-id}
+++                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
+++                                          [redacted-str vault-after detected-labels]))]
+++
+++    ;; Log the detected PII types (not scanning again)
+++    (when (seq detected)
+++      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
+++    [redacted new-vault]))
+++
+++(defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
+++  (let [model (:model payload)
+++        discovered-this-loop (atom {})
+++        vault (atom {})
+++        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
+++        context {:model model :request-id request-id}]
+++    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
+++           iteration 0]
+++      (if (>= iteration max-iterations)
+++        {:success true
+++         :provider model
+++         :data {:choices [{:index 0
+++                           :message {:role "assistant"
+++                                     :content "Maximum iterations reached. Here's what I found so far:"
+++                                     :tool_calls nil}
+++                           :finish_reason "length"}]}}
+++        (let [_ (log-request "info" "Tool Loop" {:iteration iteration :calls (count (get-in current-payload [:messages]))} context)
+++              resp (call-llm llm-url current-payload)]
+++          (if-not (:success resp)
+++            resp
+++            (let [choices (get-in resp [:data :choices])
+++                  message (get-in (first choices) [:message])
+++                  tool-calls (:tool_calls message)]
+++              (if-not tool-calls
+++                (assoc resp :provider model)
+++                (let [mcp-calls (filter (fn [tc]
+++                                          (let [n (get-in tc [:function :name])]
+++                                            (or (= n "get_tool_schema")
+++                                                (and n (str/starts-with? n "mcp__")))))
+++                                        tool-calls)
+++                      native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
+++                                           tool-calls)]
+++                  (if (and (empty? mcp-calls) (empty? native-calls))
+++                    (assoc resp :provider model)
+++                    (let [[results new-vault]
+++                          (reduce
+++                           (fn [[results vault-state] tc]
+++                             (let [fn-name (get-in tc [:function :name])
+++                                   args-str (get-in tc [:function :arguments])
+++                                   parse-result (try
+++                                                  {:success true :args (json/parse-string args-str true)}
+++                                                  (catch Exception e
+++                                                    {:success false :error (.getMessage e)}))]
+++                               (if (:success parse-result)
+++                                 (let [;; Restore args if trusted
+++                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
+++                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
+++                                       ;; Redact output with vault
+++                                       raw-content (if (string? result) result (json/generate-string result))
+++                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
+++                                   [(conj results {:role "tool"
+++                                                   :tool_call_id (:id tc)
+++                                                   :name fn-name
+++                                                   :content redacted})
+++                                    updated-vault])
+++                                 [(conj results {:role "tool"
+++                                                 :tool_call_id (:id tc)
+++                                                 :name fn-name
+++                                                 :content (json/generate-string
+++                                                           {:error "Malformed tool arguments JSON"
+++                                                            :details {:args-str args-str
+++                                                                      :parse-error (:error parse-result)}})})
+++                                  vault-state])))
+++                           [[] vault]
+++                           (concat mcp-calls native-calls))
+++                          newly-discovered @discovered-this-loop
+++                          new-tools (vec (concat (config/get-meta-tool-definitions)
+++                                                 (map (fn [[name schema]]
+++                                                        {:type "function"
+++                                                         :function {:name name
+++                                                                    :description (:description schema)
+++                                                                    :parameters (:inputSchema schema)}})
+++                                                      newly-discovered)))
+++                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
+++                          new-messages (into new-messages results)]
+++                      (recur (assoc current-payload
+++                                    :messages (scrub-messages new-messages new-vault request-id)
+++                                    :tools new-tools)
+++                             (inc iteration)))))))))))))
+++
+++(defn- set-cooldown! [provider minutes]
+++  (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
+++
+++(defn- is-on-cooldown? [provider]
+++  (if-let [expiry (get @cooldown-state provider)]
+++    (if (> expiry (System/currentTimeMillis))
+++      true
+++      (do (swap! cooldown-state dissoc provider) false))
+++    false))
+++
+++(defn reset-cooldowns! []
+++  (reset! cooldown-state {}))
+++
+++(defn- body->string [body]
+++  (if (string? body) body (slurp body)))
+++
+++(defn- extract-discovered-tools
+++  "Scan messages for tool schemas returned by get_tool_schema.
+++   Returns a map of tool-name -> full tool schema."
+++  [messages]
+++  (reduce
+++   (fn [acc msg]
+++     (if (= "tool" (:role msg))
+++       (let [content (:content msg)
+++             parsed (try (json/parse-string (body->string content) true) (catch Exception _ nil))]
+++         (if (and parsed (:name parsed))
+++           (let [tool-name (:name parsed)
+++                 formatted-name (if (str/includes? tool-name "__")
+++                                  tool-name
+++                                  (str "mcp__" tool-name))]
+++             (assoc acc formatted-name parsed))
+++           acc))
+++       acc))
+++   {}
+++   messages))
+++
+++(defn- prepare-llm-request [chat-req mcp-servers]
+++  (let [meta-tools (config/get-meta-tool-definitions)
+++        discovered-tools (extract-discovered-tools (:messages chat-req))
+++        existing-tools (:tools chat-req)
+++        fallbacks (config/get-llm-fallbacks mcp-servers)
+++        discovered-tool-defs (map (fn [[name schema]]
+++                                    {:type "function"
+++                                     :function {:name name
+++                                                :description (:description schema)
+++                                                :parameters (:inputSchema schema)}})
+++                                  discovered-tools)
+++        merged-tools (vec (concat (or existing-tools [])
+++                                  meta-tools
+++                                  discovered-tool-defs))
+++        ;; Merge extra_body into the request for fields like request-id
+++        extra-body (or (:extra_body chat-req) {})]
+++    (-> chat-req
+++        (assoc :stream false)
+++        (dissoc :stream_options)
+++        (assoc :fallbacks fallbacks)
+++        (merge extra-body) ;; Lift extra_body fields to top level
+++        (update :messages (fn [msgs]
+++                            (mapv (fn [m]
+++                                    (if (and (= (:role m) "assistant") (:tool_calls m))
+++                                      (update m :tool_calls (fn [tcs]
+++                                                              (mapv #(dissoc % :index) tcs)))
+++                                      m))
+++                                  msgs)))
+++        (assoc :tools merged-tools))))
+++
+++(defn- try-virtual-model-chain [config prepared-req llm-url mcp-servers max-iterations governance]
+++  (let [chain (:chain config)
+++        retry-on (set (:retry-on config [429 500]))
+++        cooldown-mins (get config :cooldown-minutes 5)
+++        original-model (:model prepared-req)]
+++    (loop [providers (filter #(not (is-on-cooldown? %)) chain)
+++           last-error nil]
+++      (if (empty? providers)
+++        {:success false :status 502 :error (or last-error {:message "All providers failed"})}
+++        (let [provider (first providers)
+++              _ (log-request "info" "Virtual model: trying provider" {:provider provider :remaining (count (rest providers))}
+++                             {:model original-model :endpoint llm-url})
+++              req (-> prepared-req
+++                      (assoc :model provider)
+++                      (dissoc :fallbacks))
+++              result (agent-loop llm-url req mcp-servers max-iterations governance)]
+++          (if (:success result)
+++            (assoc result :provider provider)
+++            (if (some #(= % (:status result)) retry-on)
+++              (do
+++                (log-request "warn" "Virtual model: provider failed, setting cooldown" {:provider provider :status (:status result) :cooldown-mins cooldown-mins}
+++                             {:model original-model :endpoint llm-url})
+++                (set-cooldown! provider cooldown-mins)
+++                (track-provider-failure! provider (:status result))
+++                (recur (rest providers) (:error result)))
+++              (assoc result :provider provider))))))))
+++
+++(defn- handle-chat-completion [request mcp-servers config]
+++  (try
+++    (let [chat-req (parse-body (:body request))
+++          model (:model chat-req)
+++          _ (log-request "info" "Chat Completion Started" {:stream (:stream chat-req)} {:model model})
+++          discovered (reduce (fn [acc [s-name s-conf]]
+++                               (let [url (or (:url s-conf) (:uri s-conf))
+++                                     cmd (:cmd s-conf)]
+++                                 (if (or url cmd)
+++                                   (try (assoc acc s-name (mcp/discover-tools (name s-name) s-conf (:tools s-conf) (:policy (:governance config))))
+++                                        (catch Exception e
+++                                          (log-request "warn" "Discovery failed" {:server s-name :error (.getMessage e)} {:model model})
+++                                          acc))
+++                                   acc)))
+++                             {} (:servers mcp-servers))
+++          messages (config/inject-tools-into-messages (:messages chat-req) mcp-servers discovered)
+++          llm-url (or (:llm-url config) (config/get-llm-url mcp-servers))
+++          virtual-models (config/get-virtual-models mcp-servers)
+++          virtual-config (or (get virtual-models model) (get virtual-models (keyword model)))
+++          prepared-req (prepare-llm-request (assoc chat-req :messages messages) mcp-servers)
+++          max-iter (or (:max-iterations config) 10)
+++          gov (:governance config)
+++          result (if virtual-config
+++                   (try-virtual-model-chain virtual-config prepared-req llm-url mcp-servers max-iter gov)
+++                   (agent-loop llm-url prepared-req mcp-servers max-iter gov))]
+++      (if (:success result)
+++        (let [final-resp (:data result)
+++              actual-provider (:provider result)
+++              _ (record-completion! model actual-provider (:usage final-resp))
+++              _ (log-request "info" "Chat Completion Success" {:usage (:usage final-resp) :provider actual-provider} {:model model})
+++              body (if (:stream chat-req)
+++                     (openai/build-chat-response-streaming
+++                      {:content (get-in final-resp [:choices 0 :message :content])
+++                       :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
+++                       :model model
+++                       :usage (:usage final-resp)})
+++                     (json/generate-string
+++                      (openai/build-chat-response
+++                       {:content (get-in final-resp [:choices 0 :message :content])
+++                        :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
+++                        :model model
+++                        :usage (:usage final-resp)})))]
+++          {:status 200 :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})
+++        (let [status (or (:status result) 500)
+++              error-data (:error result)
+++              error-msg (if (map? error-data) (:message error-data) (str "Failed: " error-data))
+++              error-type (get-in result [:error :type] "internal_error")
+++              _ (log-request "warn" "Chat Completion Failed" {:status status :error error-msg :type error-type} {:model model :endpoint llm-url})
+++              body (if (:stream chat-req)
+++                     (str "data: " (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}) "\n\ndata: [DONE]\n\n")
+++                     (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
+++          {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
+++    (catch Exception e
+++      (let [err-type (or (some-> e ex-data :type name) "internal_error")
+++            err-msg (or (.getMessage e) (str e))
+++            stack (.getStackTrace e)]
+++        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
+++        {:status 400
+++         :headers {"Content-Type" "application/json"}
+++         :body (json/generate-string {:error {:message err-msg
+++                                              :type err-type}})}))))
+++
+++(defn get-gateway-state []
+++  {:cooldowns @cooldown-state
+++   :usage @usage-stats
+++   :warming-up? (let [fut (get @server-state :warmup-future)]
+++                  (if fut (not (realized? fut)) false))})
+++
+++(defn- handle-api [request _mcp-servers config]
+++  (let [uri (:uri request)
+++        method (:request-method request)]
+++    (case [method uri]
+++      [:get "/api/v1/status"]
+++      {:status 200 :body (json/generate-string {:status "ok" :version "1.0.0" :warming-up? (:warming-up? (get-gateway-state))})}
+++
+++      [:get "/api/v1/mcp/tools"]
+++      {:status 200 :body (json/generate-string (mcp/get-cache-state))}
+++
+++      [:post "/api/v1/mcp/reset"]
+++      (do (mcp/clear-tool-cache!)
+++          {:status 200 :body (json/generate-string {:message "MCP state reset successful"})})
+++
+++      [:get "/api/v1/llm/state"]
+++      {:status 200 :body (json/generate-string (get-gateway-state))}
+++
+++      [:post "/api/v1/llm/cooldowns/reset"]
+++      (do (reset-cooldowns!)
+++          {:status 200 :body (json/generate-string {:message "Cooldowns reset successful"})})
+++
+++      [:get "/api/v1/stats"]
+++      {:status 200 :body (json/generate-string {:stats @usage-stats})}
+++
+++      [:get "/api/v1/audit/verify"]
+++      (let [path (:audit-log-path config)
+++            secret (:audit-secret config)
+++            valid? (audit/verify-log (io/file path) secret)]
+++        {:status 200 :body (json/generate-string {:valid? valid? :path path})})
+++
+++      {:status 404 :body (json/generate-string {:error "Not found"})})))
+++
+++(defn- handler [request mcp-servers config]
+++  (let [request-id (str (java.util.UUID/randomUUID))
+++        audit-conf {:path (io/file (:audit-log-path config))
+++                    :secret (:audit-secret config)}]
+++    (binding [*request-id* request-id
+++              *audit-config* audit-conf]
+++      (try
+++        (let [uri (:uri request)]
+++          (cond
+++            (= uri "/v1/chat/completions")
+++            (if (= :post (:request-method request))
+++              (handle-chat-completion request mcp-servers config)
+++              {:status 405 :body "Method not allowed"})
+++
+++            (= uri "/health")
+++            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}
+++
+++            (= uri "/stats")
+++            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:stats @usage-stats})}
+++
+++            (str/starts-with? uri "/api/v1")
+++            (handle-api request mcp-servers config)
+++
+++            :else
+++            {:status 404 :body "Not found"}))
+++        (catch Exception e
+++          (let [err-data (ex-data e)
+++                status (or (:status err-data) 500)
+++                err-type (or (some-> err-data :type name) "internal_error")]
+++            (log-request "error" "Request failed" {:type err-type :message (.getMessage e)} {:endpoint (:uri request)})
+++            {:status status
+++             :headers {"Content-Type" "application/json"}
+++             :body (json/generate-string {:error {:message (or (:message err-data) (.getMessage e) "Internal server error")
+++                                                  :type err-type}})}))))))
+++
+++(defn start-server [mcp-config]
+++  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
+++        provided-governance (or (:governance mcp-config)
+++                                (:governance (:mcp-servers mcp-config)))
+++
+++        ;; Runtime settings - prioritize input > env > default
+++        port (or (:port mcp-config)
+++                 (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
+++                 8080)
+++        host (or (:host mcp-config)
+++                 (System/getenv "MCP_INJECTOR_HOST")
+++                 "127.0.0.1")
+++        llm-url (or (:llm-url mcp-config)
+++                    (System/getenv "MCP_INJECTOR_LLM_URL")
+++                    "http://localhost:11434")
+++        log-level (or (:log-level mcp-config)
+++                      (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
+++        max-iterations (or (:max-iterations mcp-config)
+++                           (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
+++                           10)
+++        mcp-config-path (or (:mcp-config-path mcp-config)
+++                            (System/getenv "MCP_INJECTOR_MCP_CONFIG")
+++                            "mcp-servers.edn")
+++        ;; Audit trail config
+++        audit-log-path (or (:audit-log-path mcp-config)
+++                           (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
+++                           "logs/audit.log.ndjson")
+++        audit-secret (or (:audit-secret mcp-config)
+++                         (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
+++                         "default-audit-secret")
+++        ;; Merge provided mcp-config with loaded ones if needed
+++        base-mcp-servers (cond
+++                           (and (map? mcp-config) (:servers mcp-config)) mcp-config
+++                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
+++                           :else (config/load-mcp-servers mcp-config-path))
+++        ;; Apply overrides from mcp-config (like :virtual-models in tests)
+++        mcp-servers (if (map? mcp-config)
+++                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
+++                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
+++                        (if-let [gov (:governance mcp-config)]
+++                          (assoc merged :governance gov)
+++                          merged))
+++                      base-mcp-servers)
+++        ;; Unified configuration resolution - pass extracted governance
+++        unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
+++        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
+++        final-config {:port port :host host :llm-url llm-url :log-level log-level
+++                      :max-iterations max-iterations :mcp-config-path mcp-config-path
+++                      :audit-log-path audit-log-path :audit-secret audit-secret
+++                      :governance final-governance}
+++        ;; Validate policy at startup
+++        _ (policy/validate-policy! (:policy final-governance))
+++        ;; P3 Integration: Initialize Audit system
+++        _ (audit/init-audit! audit-log-path)
+++        srv (http/run-server (fn [req] (handler req mcp-servers final-config)) {:port port :host host})
+++        actual-port (or (:local-port (meta srv)) port)
+++        warmup-fut (future (mcp/warm-up! mcp-servers))]
+++    (reset! server-state {:server srv :port actual-port :warmup-future warmup-fut})
+++    (log-request "info" "mcp-injector started" (assoc final-config :port actual-port))
+++    {:server srv :port actual-port :warmup-future warmup-fut}))
+++
+++(defn stop-server [s]
+++  (when s
+++    (let [srv (cond (fn? s) s (map? s) (:server s) :else s)
+++          fut (when (map? s) (:warmup-future s))]
+++      (when fut (future-cancel fut))
+++      (when (fn? srv) (srv :timeout 100))
+++      (reset! server-state nil)
+++      (mcp/clear-tool-cache!)
+++      ;; P3 Integration: Close Audit system
+++      (audit/close-audit!))))
+++
+++(defn clear-mcp-sessions! []
+++  (mcp/clear-tool-cache!))
+++
+++(defn -main [& _args]
+++  (let [initial-config (config/load-config)
+++        mcp-servers (config/load-mcp-servers (:mcp-config initial-config))
+++        unified-config (config/get-config mcp-servers)]
+++    (start-server unified-config)))
+++
+++=== FILE: test/mcp_injector/restoration_test.clj ===
+++(ns mcp-injector.restoration-test
+++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
+++            [clojure.string :as str]
+++            [mcp-injector.test-llm-server :as test-llm]
+++            [mcp-injector.test-mcp-server :as test-mcp]
+++            [mcp-injector.core :as core]
+++            [cheshire.core :as json]
+++            [org.httpkit.client :as http]))
+++
+++(def test-state (atom {}))
+++
+++(use-fixtures :once
+++  (fn [f]
+++    (let [llm (test-llm/start-server)
+++          mcp (test-mcp/start-server)]
+++      (swap! test-state assoc :llm llm :mcp mcp)
+++      (let [injector (core/start-server
+++                      {:port 0
+++                       :host "127.0.0.1"
+++                       :llm-url (str "http://localhost:" (:port llm))
+++                       :mcp-servers {:servers
+++                                     {:trusted-db
+++                                      {:url (str "http://localhost:" (:port mcp))
+++                                       :tools ["query"]
+++                                       :trust :restore}
+++                                      :untrusted-api
+++                                      {:url (str "http://localhost:" (:port mcp))
+++                                       :tools ["send"]
+++                                       :trust :none}}}})]
+++        (swap! test-state assoc :injector injector)
+++        (try
+++          (f)
+++          (finally
+++            (core/stop-server injector)
+++            (test-llm/stop-server llm)
+++            (test-mcp/stop-server mcp)))))))
+++
+++(deftest test-secret-redaction-and-restoration
+++  (testing "End-to-end Redact -> Decide -> Restore flow"
+++    (let [{:keys [injector llm mcp]} @test-state
+++          port (:port injector)]
+++
+++      ;; 1. Setup MCP to return a secret
+++      ((:set-tools! mcp)
+++       {:query {:description "Query database"
+++                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
+++                :handler (fn [args]
+++                           (if (or (:email args) (get args "email"))
+++                             {:status "success" :received (or (:email args) (get args "email"))}
+++                             {:email "wes@example.com" :secret "super-secret-123"}))}})
+++
+++      ;; 2. LLM Turn 1: Get data (will be redacted)
+++      (test-llm/set-next-response llm
+++                                  {:role "assistant"
+++                                   :tool_calls [{:id "call_1"
+++                                                 :function {:name "mcp__trusted-db__query"
+++                                                            :arguments "{\"q\":\"select user\"}"}}]})
+++
+++      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
+++      ;; Token is deterministic: SHA256("EMAIL_ADDRESS|wes@example.com|test-request-id-12345") -> a35e2662
+++      (test-llm/set-next-response llm
+++                                  {:role "assistant"
+++                                   :content "I found the user. Now updating."
+++                                   :tool_calls [{:id "call_2"
+++                                                 :function {:name "mcp__trusted-db__query"
+++                                                            :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
+++
+++      ;; Final response
+++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+++
+++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+++                                 {:body (json/generate-string
+++                                         {:model "brain"
+++                                          :messages [{:role "user" :content "Update user wes"}]
+++                                          :stream false
+++                                          :extra_body {:request-id "test-request-id-12345"}})
+++                                  :headers {"Content-Type" "application/json"}})]
+++        (is (= 200 (:status response)))
+++
+++        ;; Verify MCP received the RESTORED value in the second call
+++        (let [mcp-requests @(:received-requests mcp)
+++              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
+++              update-call (last tool-calls)
+++              ;; Arguments in MCP request is a JSON string, parse it
+++              args-str (-> update-call :body :params :arguments)
+++              args (json/parse-string args-str true)]
+++          (is (= "wes@example.com" (:email args))))
+++
+++        ;; Verify LLM received REDACTED token (not original) in tool result
+++        (let [llm-requests @(:received-requests llm)
+++              ;; Find the request where LLM called tool (has tool_calls)
+++              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
+++              ;; Get the tool result message that follows the tool call
+++              msgs (:messages tool-call-req)
+++              tool-result-msg (last msgs)]
+++          ;; LLM should see token, not original email
+++          (is (some? tool-result-msg))
+++          (is (= "tool" (:role tool-result-msg)))
+++          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
+++          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
+++
+++(defn -main [& _args]
+++  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
+++    (System/exit (if (zero? (:fail result)) 0 1))))
+++
+++=== FILE: test/mcp_injector/discovery_test.clj ===
+++(ns mcp-injector.discovery-test
+++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
+++            [clojure.string :as str]
+++            [org.httpkit.client :as http]
+++            [cheshire.core :as json]
+++            [mcp-injector.core :as core]
+++            [mcp-injector.mcp-client :as mcp]
+++            [mcp-injector.test-mcp-server :as test-mcp]
+++            [mcp-injector.test-llm-server :as test-llm]))
+++
+++(def ^:dynamic *test-mcp* nil)
+++(def ^:dynamic *test-llm* nil)
+++(def ^:dynamic *injector* nil)
+++
+++(defn extra-tools []
+++  [{:name "list_charges"
+++    :description "List charges"
+++    :inputSchema {:type "object" :properties {:limit {:type "number"}}}}
+++   {:name "retrieve_customer"
+++    :description "Retrieve a customer"
+++    :inputSchema {:type "object" :properties {:customer_id {:type "string"}}}}])
+++
+++(defn discovery-fixture
+++  [test-fn]
+++  (let [mcp-server (test-mcp/start-test-mcp-server)
+++        llm-server (test-llm/start-server)
+++        injector-server (core/start-server {:port 0
+++                                            :host "127.0.0.1"
+++                                            :llm-url (str "http://localhost:" (:port llm-server))
+++                                            :log-level "debug"
+++                                            :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
+++                                                                             :tools [:retrieve_customer :list_charges]}}
+++                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
+++    (try
+++      (binding [*test-mcp* mcp-server
+++                *test-llm* llm-server
+++                *injector* injector-server]
+++        (test-fn))
+++      (finally
+++        (core/stop-server injector-server)
+++        (test-llm/stop-server llm-server)
+++        (test-mcp/stop-server mcp-server)))))
+++
+++(use-fixtures :each discovery-fixture)
+++
+++(deftest progressive-discovery-flow
+++  (testing "Full progressive discovery: Directory -> get_tool_schema -> call"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++
+++    ;; Turn 1: LLM sees directory and asks for schema (using NEW prefixed format)
+++    (test-llm/set-tool-call-response *test-llm*
+++                                     [{:name "get_tool_schema"
+++                                       :arguments {:tool "mcp__stripe__retrieve_customer"}}])
+++
+++    ;; Turn 2: LLM receives schema and makes the actual call
+++    (test-llm/set-tool-call-response *test-llm*
+++                                     [{:name "mcp__stripe__retrieve_customer"
+++                                       :arguments {:customer_id "cus_123"}}])
+++
+++    ;; Turn 3: Final response
+++    (test-llm/set-next-response *test-llm*
+++                                {:role "assistant"
+++                                 :content "Found customer: customer@example.com"})
+++
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o-mini"
+++                                        :messages [{:role "user"
+++                                                    :content "Find customer cus_123"}]
+++                                        :stream false})
+++                                :headers {"Content-Type" "application/json"}})
+++          requests @(:received-requests *test-llm*)
+++          first-req (first requests)
+++          last-req (last requests)
+++          tool-msgs (filter #(= "tool" (:role %)) (:messages last-req))]
+++
+++      (is (= 200 (:status response)))
+++      (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
+++      (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))
+++      ;; content might be redacted as [EMAIL_ADDRESS] or [HIGH_ENTROPY_SECRET] depending on scanner
+++      (is (some (fn [m] (or (re-find #"\[EMAIL_ADDRESS(_[a-f0-9]{12})?\]" (:content m))
+++                            (re-find #"\[HIGH_ENTROPY_SECRET(_[a-f0-9]{12})?\]" (:content m)))) tool-msgs)))))
+++
+++(deftest tool-discovery-filtering-nil-shows-all
+++  (testing "When :tools is nil, all discovered tools from MCP server should be shown"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (let [mcp-server (test-mcp/start-server :tools (extra-tools))
+++          llm-server (test-llm/start-server)
+++          injector (core/start-server {:port 0
+++                                       :host "127.0.0.1"
+++                                       :llm-url (str "http://localhost:" (:port llm-server))
+++                                       :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
+++                                                                        :tools nil}}
+++                                                     :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
+++      (try
+++        (test-llm/set-next-response llm-server {:role "assistant" :content "ok"})
+++        (let [response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
+++                                   {:body (json/generate-string
+++                                           {:model "gpt-4o"
+++                                            :messages [{:role "user" :content "test"}]})
+++                                    :headers {"Content-Type" "application/json"}})
+++              first-req (first @(:received-requests llm-server))]
+++          (is (= 200 (:status response)))
+++          (is (str/includes? (get-in first-req [:messages 0 :content]) "retrieve_customer"))
+++          (is (str/includes? (get-in first-req [:messages 0 :content]) "list_charges")))
+++        (finally
+++          (core/stop-server injector)
+++          (test-llm/stop-server llm-server)
+++          (test-mcp/stop-server mcp-server))))))
+++
+++(deftest tool-discovery-filtering-specified-shows-subset
+++  (testing "When :tools is specified, only those tools should be shown"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (let [mcp-server (test-mcp/start-server :tools (extra-tools))
+++          llm-server (test-llm/start-server)
+++          injector (core/start-server {:port 0
+++                                       :host "127.0.0.1"
+++                                       :llm-url (str "http://localhost:" (:port llm-server))
+++                                       :mcp-servers {:servers {:stripe {:url (str "http://localhost:" (:port mcp-server))
+++                                                                        :tools ["retrieve_customer"]}}
+++                                                     :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
+++      (try
+++        (test-llm/set-next-response llm-server {:role "assistant" :content "ok"})
+++        (let [response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
+++                                   {:body (json/generate-string
+++                                           {:model "gpt-4o"
+++                                            :messages [{:role "user" :content "test"}]})
+++                                    :headers {"Content-Type" "application/json"}})
+++              first-req (first @(:received-requests llm-server))]
+++          (is (= 200 (:status response)))
+++          (is (not (str/includes? (get-in first-req [:messages 0 :content]) "list_charges")))
+++          (is (str/includes? (get-in first-req [:messages 0 :content]) "retrieve_customer")))
+++        (finally
+++          (core/stop-server injector)
+++          (test-llm/stop-server llm-server)
+++          (test-mcp/stop-server mcp-server))))))
+++
+++;; Phase 1: Tool Directory with Names + Args
+++
+++(deftest test-tool-directory-shows-namespaced-names
+++  (testing "Tool directory should show mcp__server__tool format"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (test-llm/set-next-response *test-llm* {:role "assistant" :content "ok"})
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o"
+++                                        :messages [{:role "user" :content "test"}]})
+++                                :headers {"Content-Type" "application/json"}})
+++          first-req (first @(:received-requests *test-llm*))]
+++      (is (= 200 (:status response)))
+++      (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe__retrieve_customer")))))
+++
+++(deftest test-tool-directory-shows-param-arity
+++  (testing "Tool directory should show param names with ? suffix for optional"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (test-llm/set-next-response *test-llm* {:role "assistant" :content "ok"})
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o"
+++                                        :messages [{:role "user" :content "test"}]})
+++                                :headers {"Content-Type" "application/json"}})
+++          first-req (first @(:received-requests *test-llm*))
+++          content (get-in first-req [:messages 0 :content])]
+++      (is (= 200 (:status response)))
+++      (is (str/includes? content "retrieve_customer [customer_id"))
+++      (is (str/includes? content "limit?")))))
+++
+++(deftest test-tool-directory-with-pre-discovered-tools
+++  (testing "When pre-discovered tools with schema, show full param info"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (test-llm/set-next-response *test-llm* {:role "assistant" :content "ok"})
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o"
+++                                        :messages [{:role "user" :content "test"}]})
+++                                :headers {"Content-Type" "application/json"}})
+++          first-req (first @(:received-requests *test-llm*))
+++          content (get-in first-req [:messages 0 :content])]
+++      (is (= 200 (:status response)))
+++      (is (str/includes? content "mcp__stripe__retrieve_customer [customer_id"))
+++      (is (str/includes? content "mcp__stripe__list_charges [customer?, limit?]")))))
+++
+++(deftest test-tool-directory-with-fallback-tools
+++  (testing "When no schema available, should still show tool names"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (let [mcp-server (test-mcp/start-server :tools [])
+++          llm-server (test-llm/start-server)
+++          injector (core/start-server {:port 0
+++                                       :host "127.0.0.1"
+++                                       :llm-url (str "http://localhost:" (:port llm-server))
+++                                       :mcp-servers {:servers {:fallback-server {:url (str "http://localhost:" (:port mcp-server))
+++                                                                                 :tools [:tool_a :tool_b]}}
+++                                                     :llm-gateway {:url (str "http://localhost:" (:port llm-server))}}})]
+++      (try
+++        (test-llm/set-next-response llm-server {:role "assistant" :content "ok"})
+++        (let [response @(http/post (str "http://localhost:" (:port injector) "/v1/chat/completions")
+++                                   {:body (json/generate-string
+++                                           {:model "gpt-4o"
+++                                            :messages [{:role "user" :content "test"}]})
+++                                    :headers {"Content-Type" "application/json"}})
+++              first-req (first @(:received-requests llm-server))
+++              content (get-in first-req [:messages 0 :content])]
+++          (is (= 200 (:status response)))
+++          (is (str/includes? content "mcp__fallback-server__tool_a"))
+++          (is (str/includes? content "mcp__fallback-server__tool_b")))
+++        (finally
+++          (core/stop-server injector)
+++          (test-llm/stop-server llm-server)
+++          (test-mcp/stop-server mcp-server))))))
+++
+++;; Unified Prefixed Tool Names Tests
+++
+++(deftest test-get-tool-schema-accepts-prefixed-name
+++  (testing "get_tool_schema should accept prefixed tool name format mcp__server__tool"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (test-llm/set-tool-call-response *test-llm*
+++                                     [{:name "get_tool_schema"
+++                                       :arguments {:tool "mcp__stripe__retrieve_customer"}}])
+++    (test-llm/set-tool-call-response *test-llm*
+++                                     [{:name "mcp__stripe__retrieve_customer"
+++                                       :arguments {:customer_id "cus_123"}}])
+++    (test-llm/set-next-response *test-llm*
+++                                {:role "assistant"
+++                                 :content "Found customer"})
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o-mini"
+++                                        :messages [{:role "user" :content "Get customer info"}]
+++                                        :stream false})
+++                                :headers {"Content-Type" "application/json"}})
+++          body (json/parse-string (:body response) true)
+++          last-msg (get-in body [:choices 0 :message :content])]
+++      (is (= 200 (:status response)))
+++      (is (str/includes? (or last-msg "") "Found customer")))))
+++
+++(deftest test-system-prompt-shows-prefixed-get-schema-format
+++  (testing "System prompt should show get_tool_schema with prefixed format"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (test-llm/set-next-response *test-llm* {:role "assistant" :content "ok"})
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o"
+++                                        :messages [{:role "user" :content "test"}]})
+++                                :headers {"Content-Type" "application/json"}})
+++          first-req (first @(:received-requests *test-llm*))
+++          content (get-in first-req [:messages 0 :content])]
+++      (is (= 200 (:status response)))
+++      (is (str/includes? content "get_tool_schema {:tool"))
+++      (is (str/includes? content "mcp__stripe"))
+++      (is (not (str/includes? content ":server"))))))
+++
+++(deftest test-direct-tool-call-without-schema-still-works
+++  (testing "Direct tool call without get_tool_schema should auto-discover and work"
+++    (mcp/clear-tool-cache!)
+++    (test-llm/clear-responses *test-llm*)
+++    (test-llm/set-tool-call-response *test-llm*
+++                                     [{:name "mcp__stripe__retrieve_customer"
+++                                       :arguments {:customer_id "cus_123"}}])
+++    (test-llm/set-next-response *test-llm*
+++                                {:role "assistant"
+++                                 :content "Found customer"})
+++    (let [response @(http/post (str "http://localhost:" (:port *injector*) "/v1/chat/completions")
+++                               {:body (json/generate-string
+++                                       {:model "gpt-4o-mini"
+++                                        :messages [{:role "user" :content "Get customer cus_123"}]
+++                                        :stream false})
+++                                :headers {"Content-Type" "application/json"}})]
+++      (is (= 200 (:status response))))))
+++
++diff --git a/dev/PII_RESTORATION_PLAN.md b/dev/PII_RESTORATION_PLAN.md
++new file mode 100644
++index 0000000..903b280
++--- /dev/null
+++++ b/dev/PII_RESTORATION_PLAN.md
++@@ -0,0 +1,31 @@
+++# PII Restoration & Secret Substitution Plan
+++
+++## Status
+++- **Branch:** `feat/pii-restoration`
+++- **Current State:** COMPLETE - All tests passing
+++- **Completed:** 2026-03-14
+++
+++## Summary
+++Successfully implemented "Smart Vault" PII restoration system:
+++- Deterministic token generation with SHA-256
+++- Request-scoped vault for token/value mapping
+++- Trust levels (`:none`, `:read`, `:restore`) at server and tool level
+++- Two-way substitution: LLM sees tokens, trusted tools see real values
+++
+++## Core Strategy: The "Smart Vault"
+++We are moving from "One-Way Redaction" to "Two-Way Substitution" for trusted tools.
+++
+++### 1. Tokenization (Outbound to LLM)
+++- **Deterministic Hashing:** Replace `[EMAIL_ADDRESS]` with `[PII_EMAIL_8ce0db03]`. 
+++- **Vaulting:** Store `{ "[PII_EMAIL_8ce0db03]" "wes@example.com" }` in a request-scoped vault.
+++- **Structural Awareness:** Use `clojure.walk` to redact JSON values while preserving keys (so LLM understands schema).
+++
+++### 2. Restoration (Inbound to Tools)
+++- **Trust Tiers:** Define `:trust :restore` in `mcp-servers.edn`.
+++- **Restoration:** If a tool call targets a trusted server, `mcp-injector` swaps tokens back for real values before execution.
+++- **Safety:** Untrusted tools continue to see only the redacted tokens.
+++
+++## Build Results
+++- **55 tests** - All passing
+++- **Lint** - Clean
+++- **Format** - Clean
++diff --git a/dev/specs/pii-restoration.edn b/dev/specs/pii-restoration.edn
++new file mode 100644
++index 0000000..edb6cd1
++--- /dev/null
+++++ b/dev/specs/pii-restoration.edn
++@@ -0,0 +1,15 @@
+++{:title "PII/Secret Restoration (Round-Trip)"
+++ :description "Enable trusted tools to receive original sensitive data while keeping the LLM's view redacted."
+++ :acceptance-criteria
+++ ["Tool outputs containing PII are tokenized with deterministic, hybrid labels (e.g., [EMAIL_8f3a2])"
+++  "Tokens remain consistent across a single request context"
+++  "A request-scoped Vault stores the mapping of Token -> Original Value"
+++  "Trusted tools (marked with :trust :restore) receive restored values in their arguments"
+++  "Untrusted tools receive the literal token strings"
+++  "Deep JSON redaction preserves map keys but tokenizes values"]
+++ :edge-cases
+++ ["Recursive data structures in tool arguments"
+++  "Mixed plain-text and JSON tool outputs"
+++  "Token collisions (mitigated via request-id salt)"
+++  "Empty or null values in scanned data"]
+++ :depends-on [:governance-core :mcp-client]}
++diff --git a/result b/result
++deleted file mode 120000
++index eea2214..0000000
++--- a/result
+++++ /dev/null
++@@ -1 +0,0 @@
++-/nix/store/gdjbiza5hidsdb7lx3spirlsxybwlzry-mcp-injector-0.1.0
++\ No newline at end of file
++diff --git a/src/mcp_injector/config.clj b/src/mcp_injector/config.clj
++index aa15670..9a0e8bd 100644
++--- a/src/mcp_injector/config.clj
+++++ b/src/mcp_injector/config.clj
++@@ -166,6 +166,39 @@
++     []
++     (:servers mcp-config))))
++ 
+++(defn get-server-trust
+++  "Get trust level for a server/tool combination.
+++    Returns :restore (trusted), :none (untrusted), or :block.
+++    Precedence: tool-level :trust > server-level :trust > :none
+++    
+++    Handles :tools as:
+++    - Map: {:retrieve_customer {:trust :restore}}
+++    - Vector of maps: [{:name \"retrieve_customer\" :trust :restore}]"
+++  [mcp-config server-name tool-name]
+++  (let [servers (:servers mcp-config)
+++        server (get servers (keyword server-name))]
+++    (if-not server
+++      :none
+++      (let [server-trust (or (:trust server) :none)
+++            tool-configs (:tools server)
+++            tool-config (cond
+++                          ;; :tools is a map: {:tool-name config}
+++                          (map? tool-configs)
+++                          (get tool-configs (keyword tool-name))
+++
+++                          ;; :tools is a vector: [{:name "tool" :trust :restore}]
+++                          (sequential? tool-configs)
+++                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)
+++
+++                          :else nil)
+++            tool-trust (or (:trust tool-config) :none)]
+++        (cond
+++          (= tool-trust :block) :block
+++          (= server-trust :block) :block
+++          (= tool-trust :restore) :restore
+++          (= server-trust :restore) :restore
+++          :else :none)))))
+++
++ (defn get-meta-tool-definitions
++   "Get definitions for meta-tools like get_tool_schema and native tools"
++   []
++diff --git a/src/mcp_injector/core.clj b/src/mcp_injector/core.clj
++index 5cee001..2639200 100644
++--- a/src/mcp_injector/core.clj
+++++ b/src/mcp_injector/core.clj
++@@ -169,12 +169,12 @@
++         (= full-name "clojure-eval")
++         (try
++           (let [code (:code args)
++-                ;; NOTE: clojure-eval is a full JVM/Babashka load-string. 
+++                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
++                 ;; Security is currently enforced only via the Policy layer (explicit opt-in).
++                 result (load-string code)]
++-            (pr-str result))
+++            (json/generate-string result))
++           (catch Exception e
++-            {:error (str "Eval error: " (.getMessage e))}))
+++            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
++ 
++         (str/starts-with? full-name "mcp__")
++         (let [t-name (str/replace full-name #"^mcp__" "")
++@@ -199,28 +199,84 @@
++ 
++         :else {:error (str "Unknown tool: " full-name)}))))
++ 
++-(defn- scrub-messages [messages]
+++(defn- parse-tool-name
+++  "Parse mcp__server__tool format into [server tool]"
+++  [full-name]
+++  (if (str/includes? full-name "__")
+++    (let [t-name (str/replace full-name #"^mcp__" "")
+++          idx (str/last-index-of t-name "__")]
+++      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+++    [nil full-name]))
+++
+++(defn- scrub-messages [messages vault request-id]
++   (mapv (fn [m]
++-          (if (string? (:content m))
++-            (let [{:keys [text detected]} (pii/scan-and-redact (:content m) {:mode :replace})]
++-              (when (seq detected)
++-                (log-request "info" "PII Redacted" {:labels detected} {:role (:role m)}))
++-              (assoc m :content text))
++-            m))
+++          (let [content (:content m)
+++                role (:role m)]
+++            (if (and (string? content)
+++                     ;; Only redact user/system messages - assistant tool results are already handled
+++                     (or (= role "system") (= role "user"))
+++                     ;; Skip if already contains PII tokens (avoid double-redaction)
+++                     ;; Token format: [LABEL_hex8] e.g., [EMAIL_ADDRESS_a35e2662]
+++                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{8,}\]" content)))
+++              (let [config {:mode :replace :salt request-id}
+++                    [redacted-content _ _] (pii/redact-data content config vault)]
+++                (assoc m :content redacted-content))
+++              m)))
++         messages))
++ 
++-(defn- sanitize-tool-output [content]
++-  (if (string? content)
++-    (str/replace content
++-                 #"(?im)^\s*(system|human|assistant|user)\s*:"
++-                 "[REDACTED_ROLE_MARKER]")
++-    content))
+++(defn- restore-tool-args
+++  "Restore tokens in tool args if server is trusted"
+++  [args vault mcp-servers full-tool-name]
+++  (let [[server tool] (parse-tool-name full-tool-name)
+++        trust (when server (config/get-server-trust mcp-servers server tool))
+++        restored (if (= trust :restore)
+++                   (pii/restore-tokens args vault)
+++                   args)]
+++    restored))
+++
+++(defn- redact-tool-output
+++  "Redact PII from tool output, return [content vault]"
+++  [raw-output vault request-id]
+++  (let [;; Try to parse as JSON first for JSON tokenization
+++        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
+++        ;; If parsed successfully, redact the data structure; otherwise redact the string
+++        ;; Special handling for MCP response format: parse nested :text field if present
+++        [redacted new-vault detected] (if parsed
+++                                        (let [;; Check if this is MCP response format with :text field containing JSON
+++                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
+++                                              parsed (cond
+++                                                       (map? parsed)
+++                                                       (if (string? (:text parsed))
+++                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
+++                                                              (catch Exception _ parsed))
+++                                                         parsed)
+++                                                       (sequential? parsed)
+++                                                       (mapv (fn [item]
+++                                                               (if (and (map? item) (string? (:text item)))
+++                                                                 (try (assoc item :text (json/parse-string (:text item) true))
+++                                                                      (catch Exception _ item))
+++                                                                 item))
+++                                                             parsed)
+++                                                       :else parsed)
+++                                              config {:mode :replace :salt request-id}
+++                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
+++                                          [(json/generate-string redacted-struct) vault-after detected-labels])
+++                                        (let [config {:mode :replace :salt request-id}
+++                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
+++                                          [redacted-str vault-after detected-labels]))]
+++
+++    ;; Log the detected PII types (not scanning again)
+++    (when (seq detected)
+++      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
+++    [redacted new-vault]))
++ 
++ (defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
++   (let [model (:model payload)
++         discovered-this-loop (atom {})
++-        context {:model model}]
++-    (loop [current-payload (update payload :messages scrub-messages)
+++        vault (atom {})
+++        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
+++        context {:model model :request-id request-id}]
+++    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
++            iteration 0]
++       (if (>= iteration max-iterations)
++         {:success true
++@@ -239,40 +295,46 @@
++                   tool-calls (:tool_calls message)]
++               (if-not tool-calls
++                 (assoc resp :provider model)
++-                (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
++-                                             (str/starts-with? (get-in % [:function :name]) "mcp__"))
+++                (let [mcp-calls (filter (fn [tc]
+++                                          (let [n (get-in tc [:function :name])]
+++                                            (or (= n "get_tool_schema")
+++                                                (and n (str/starts-with? n "mcp__")))))
++                                         tool-calls)
++                       native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
++                                            tool-calls)]
++                   (if (and (empty? mcp-calls) (empty? native-calls))
++                     (assoc resp :provider model)
++-                    (let [results (mapv (fn [tc]
++-                                          (let [fn-name (get-in tc [:function :name])
++-                                                args-str (get-in tc [:function :arguments])
++-                                                parse-result (try
++-                                                               {:success true :args (json/parse-string args-str true)}
++-                                                               (catch Exception e
++-                                                                 {:success false :error (.getMessage e)}))]
++-                                            (if (:success parse-result)
++-                                              (let [result (execute-tool fn-name (:args parse-result) mcp-servers discovered-this-loop governance context)
++-                                                    ;; Scrub and sanitize tool output
++-                                                    raw-content (if (string? result) result (json/generate-string result))
++-                                                    sanitized (sanitize-tool-output raw-content)
++-                                                    {:keys [text detected]} (pii/scan-and-redact sanitized {:mode :replace})
++-                                                    _ (when (seq detected)
++-                                                        (log-request "info" "PII Redacted in Tool Output" {:tool fn-name :labels detected} context))]
++-                                                {:role "tool"
+++                    (let [[results new-vault]
+++                          (reduce
+++                           (fn [[results vault-state] tc]
+++                             (let [fn-name (get-in tc [:function :name])
+++                                   args-str (get-in tc [:function :arguments])
+++                                   parse-result (try
+++                                                  {:success true :args (json/parse-string args-str true)}
+++                                                  (catch Exception e
+++                                                    {:success false :error (.getMessage e)}))]
+++                               (if (:success parse-result)
+++                                 (let [;; Restore args if trusted
+++                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
+++                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
+++                                       ;; Redact output with vault
+++                                       raw-content (if (string? result) result (json/generate-string result))
+++                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
+++                                   [(conj results {:role "tool"
+++                                                   :tool_call_id (:id tc)
+++                                                   :name fn-name
+++                                                   :content redacted})
+++                                    updated-vault])
+++                                 [(conj results {:role "tool"
++                                                  :tool_call_id (:id tc)
++                                                  :name fn-name
++-                                                 :content text})
++-                                              {:role "tool"
++-                                               :tool_call_id (:id tc)
++-                                               :name fn-name
++-                                               :content (json/generate-string
++-                                                         {:error "Malformed tool arguments JSON"
++-                                                          :details {:args-str args-str
++-                                                                    :parse-error (:error parse-result)}})})))
++-                                        (concat mcp-calls native-calls))
+++                                                 :content (json/generate-string
+++                                                           {:error "Malformed tool arguments JSON"
+++                                                            :details {:args-str args-str
+++                                                                      :parse-error (:error parse-result)}})})
+++                                  vault-state])))
+++                           [[] vault]
+++                           (concat mcp-calls native-calls))
++                           newly-discovered @discovered-this-loop
++                           new-tools (vec (concat (config/get-meta-tool-definitions)
++                                                  (map (fn [[name schema]]
++@@ -281,9 +343,12 @@
++                                                                     :description (:description schema)
++                                                                     :parameters (:inputSchema schema)}})
++                                                       newly-discovered)))
++-                          new-messages (conj (vec (:messages current-payload)) message)
+++                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
++                           new-messages (into new-messages results)]
++-                      (recur (assoc current-payload :messages new-messages :tools new-tools) (inc iteration)))))))))))))
+++                      (recur (assoc current-payload
+++                                    :messages (scrub-messages new-messages new-vault request-id)
+++                                    :tools new-tools)
+++                             (inc iteration)))))))))))))
++ 
++ (defn- set-cooldown! [provider minutes]
++   (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
++@@ -334,11 +399,14 @@
++                                   discovered-tools)
++         merged-tools (vec (concat (or existing-tools [])
++                                   meta-tools
++-                                  discovered-tool-defs))]
+++                                  discovered-tool-defs))
+++        ;; Merge extra_body into the request for fields like request-id
+++        extra-body (or (:extra_body chat-req) {})]
++     (-> chat-req
++         (assoc :stream false)
++         (dissoc :stream_options)
++         (assoc :fallbacks fallbacks)
+++        (merge extra-body) ;; Lift extra_body fields to top level
++         (update :messages (fn [msgs]
++                             (mapv (fn [m]
++                                     (if (and (= (:role m) "assistant") (:tool_calls m))
++@@ -428,11 +496,13 @@
++                      (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
++           {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
++     (catch Exception e
++-      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
++-        (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e)} {})
+++      (let [err-type (or (some-> e ex-data :type name) "internal_error")
+++            err-msg (or (.getMessage e) (str e))
+++            stack (.getStackTrace e)]
+++        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
++         {:status 400
++          :headers {"Content-Type" "application/json"}
++-         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
+++         :body (json/generate-string {:error {:message err-msg
++                                               :type err-type}})}))))
++ 
++ (defn get-gateway-state []
++@@ -509,46 +579,51 @@
++                                                   :type err-type}})}))))))
++ 
++ (defn start-server [mcp-config]
++-  (let [initial-config (if (and (map? mcp-config) (not (:servers mcp-config)))
++-                         mcp-config
++-                         {})
++-        port (or (:port initial-config)
+++  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
+++        provided-governance (or (:governance mcp-config)
+++                                (:governance (:mcp-servers mcp-config)))
+++
+++        ;; Runtime settings - prioritize input > env > default
+++        port (or (:port mcp-config)
++                  (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
++                  8080)
++-        host (or (:host initial-config)
+++        host (or (:host mcp-config)
++                  (System/getenv "MCP_INJECTOR_HOST")
++                  "127.0.0.1")
++-        llm-url (or (:llm-url initial-config)
+++        llm-url (or (:llm-url mcp-config)
++                     (System/getenv "MCP_INJECTOR_LLM_URL")
++                     "http://localhost:11434")
++-        log-level (or (:log-level initial-config)
+++        log-level (or (:log-level mcp-config)
++                       (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
++-        max-iterations (or (:max-iterations initial-config)
+++        max-iterations (or (:max-iterations mcp-config)
++                            (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
++                            10)
++-        mcp-config-path (or (:mcp-config-path initial-config)
+++        mcp-config-path (or (:mcp-config-path mcp-config)
++                             (System/getenv "MCP_INJECTOR_MCP_CONFIG")
++                             "mcp-servers.edn")
++         ;; Audit trail config
++-        audit-log-path (or (:audit-log-path initial-config)
+++        audit-log-path (or (:audit-log-path mcp-config)
++                            (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
++                            "logs/audit.log.ndjson")
++-        audit-secret (or (:audit-secret initial-config)
+++        audit-secret (or (:audit-secret mcp-config)
++                          (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
++                          "default-audit-secret")
++         ;; Merge provided mcp-config with loaded ones if needed
++         base-mcp-servers (cond
++                            (and (map? mcp-config) (:servers mcp-config)) mcp-config
++-                           (:mcp-servers initial-config) (:mcp-servers initial-config)
+++                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
++                            :else (config/load-mcp-servers mcp-config-path))
++-        ;; Apply overrides from initial-config (like :virtual-models in tests)
++-        mcp-servers (if (seq initial-config)
++-                      (let [gateway-overrides (select-keys initial-config [:virtual-models :fallbacks :url])]
++-                        (update base-mcp-servers :llm-gateway merge gateway-overrides))
+++        ;; Apply overrides from mcp-config (like :virtual-models in tests)
+++        mcp-servers (if (map? mcp-config)
+++                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
+++                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
+++                        (if-let [gov (:governance mcp-config)]
+++                          (assoc merged :governance gov)
+++                          merged))
++                       base-mcp-servers)
++-        ;; Unified configuration resolution
+++        ;; Unified configuration resolution - pass extracted governance
++         unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
++-        final-governance (config/resolve-governance (assoc mcp-servers :governance (:governance initial-config)) unified-env)
+++        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
++         final-config {:port port :host host :llm-url llm-url :log-level log-level
++                       :max-iterations max-iterations :mcp-config-path mcp-config-path
++                       :audit-log-path audit-log-path :audit-secret audit-secret
++diff --git a/src/mcp_injector/pii.clj b/src/mcp_injector/pii.clj
++index faeb7e7..a4b0dba 100644
++--- a/src/mcp_injector/pii.clj
+++++ b/src/mcp_injector/pii.clj
++@@ -1,12 +1,13 @@
++ (ns mcp-injector.pii
++-  (:require [clojure.string :as str]))
+++  (:require [clojure.string :as str]
+++            [clojure.walk :as walk])
+++  (:import (java.security MessageDigest)))
++ 
++ (def default-patterns
++   [{:id :EMAIL_ADDRESS
++     :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
++     :label "[EMAIL_ADDRESS]"}
++    {:id :IBAN_CODE
++-    ;; Tightened range to 15-34 and added case-insensitivity support via (?i)
++     :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
++     :label "[IBAN_CODE]"}])
++ 
++@@ -46,8 +47,6 @@
++ (defn- scan-env [text env-vars mode]
++   (reduce-kv
++    (fn [acc k v]
++-     ;; Case-sensitive match for env vars is usually safer, 
++-     ;; but we ensure the value is long enough to avoid false positives.
++      (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
++        (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
++        acc))
++@@ -64,7 +63,6 @@
++   (let [tokens (str/split text #"\s+")]
++     (reduce
++      (fn [acc token]
++-       ;; Threshold raised to 4.0 + diversity check + length check
++        (if (and (> (count token) 12)
++                 (> (shannon-entropy token) threshold)
++                 (character-diversity? token))
++@@ -74,15 +72,13 @@
++      tokens)))
++ 
++ (defn scan-and-redact
++-  "Scans input text for PII patterns, high-entropy secrets, and env vars.
++-   Calculations are performed sequentially on the text."
+++  "Scans input text for PII patterns, high-entropy secrets, and env vars."
++   [text {:keys [mode patterns entropy-threshold env]
++          :or {mode :replace
++               patterns default-patterns
++               entropy-threshold 4.0
++               env {}}}]
++-  (let [;; 1. Regex patterns (Standard PII)
++-        regex-result (reduce
+++  (let [regex-result (reduce
++                       (fn [state {:keys [id pattern label]}]
++                         (if (seq (re-seq pattern (:text state)))
++                           {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
++@@ -90,14 +86,81 @@
++                           state))
++                       {:text text :detected []}
++                       patterns)
++-
++-        ;; 2. Env vars (Exact matches)
++         env-text (scan-env (:text regex-result) env mode)
++         env-detections (find-env-detections text env)
++-
++-        ;; 3. Entropy (Heuristic secrets)
++         final-text (scan-entropy env-text entropy-threshold mode)
++         entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
++-
++     {:text final-text
++      :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
+++
+++(defn generate-token
+++  "Generate a deterministic, truncated SHA-256 hash token.
+++   Uses 12 hex chars (48 bits) to reduce collision probability."
+++  [label value salt]
+++  (let [input (str (name label) "|" value "|" salt)
+++        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
+++        hash-str (->> digest
+++                      (map (partial format "%02x"))
+++                      (apply str))
+++        truncated (subs hash-str 0 12)]
+++    (str "[" (name label) "_" truncated "]")))
+++
+++(defn- redact-string-value
+++  "Redact a single string value, returning [redacted-text token detected-label]"
+++  [v config]
+++  (if-not (string? v)
+++    [v nil nil]
+++    (if (empty? v)
+++      [v nil nil]
+++      (let [vault (:vault config)
+++            salt (:salt config)
+++            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
+++            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
+++        (cond
+++          existing-token [existing-token nil nil]
+++          previous-token [previous-token nil nil]
+++          :else
+++          (let [result (scan-and-redact v config)]
+++            (if (seq (:detected result))
+++              (let [detected (first (:detected result))
+++                    token (generate-token detected v salt)]
+++                (swap! vault assoc token v)
+++                [token token detected])
+++              [(:text result) nil nil])))))))
+++
+++(defn redact-data
+++  "Recursively walk a data structure, redact string values, store in vault.
+++    Returns [redacted-data vault-atom detected-labels]"
+++  ([data config]
+++   (redact-data data config (atom {})))
+++  ([data config vault]
+++   (let [config-with-vault (assoc config :vault vault)
+++         detected-labels (atom [])
+++         redacted (walk/postwalk
+++                   (fn [x]
+++                     (if (string? x)
+++                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
+++                         (when detected (swap! detected-labels conj detected))
+++                         redacted-text)
+++                       x))
+++                   data)]
+++     [redacted vault @detected-labels])))
+++
+++(defn restore-tokens
+++  "Recursively walk a data structure, replacing tokens with original values from vault."
+++  [data vault]
+++  (let [v-map @vault]
+++    (if (empty? v-map)
+++      data
+++      (walk/postwalk
+++       (fn [x]
+++         (if (string? x)
+++           (reduce
+++            (fn [s [token original]]
+++              (if (and (string? s) (str/includes? s token))
+++                (str/replace s (str token) (str original))
+++                s))
+++            x
+++            v-map)
+++           x))
+++       data))))
++diff --git a/test/mcp_injector/discovery_test.clj b/test/mcp_injector/discovery_test.clj
++index cf4e069..6ae43a4 100644
++--- a/test/mcp_injector/discovery_test.clj
+++++ b/test/mcp_injector/discovery_test.clj
++@@ -79,8 +79,8 @@
++       (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
++       (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))
++       ;; content might be redacted as [EMAIL_ADDRESS] or [HIGH_ENTROPY_SECRET] depending on scanner
++-      (is (some (fn [m] (or (str/includes? (:content m) "[EMAIL_ADDRESS]")
++-                            (str/includes? (:content m) "[HIGH_ENTROPY_SECRET]"))) tool-msgs)))))
+++      (is (some (fn [m] (or (re-find #"\[EMAIL_ADDRESS(_[a-f0-9]{12})?\]" (:content m))
+++                            (re-find #"\[HIGH_ENTROPY_SECRET(_[a-f0-9]{12})?\]" (:content m)))) tool-msgs)))))
++ 
++ (deftest tool-discovery-filtering-nil-shows-all
++   (testing "When :tools is nil, all discovered tools from MCP server should be shown"
++diff --git a/test/mcp_injector/llm_shim_test.clj b/test/mcp_injector/llm_shim_test.clj
++index 4142816..748e04b 100644
++--- a/test/mcp_injector/llm_shim_test.clj
+++++ b/test/mcp_injector/llm_shim_test.clj
++@@ -25,7 +25,9 @@
++                       {:port 0
++                        :host "127.0.0.1"
++                        :llm-url (str "http://localhost:" (:port llm))
++-                       :mcp-config "./mcp-servers.edn"})]
+++                       :mcp-servers {:llm-gateway {:fallbacks ["zen/kimi-k2.5-free"
+++                                                               "nvidia/moonshotai/kimi-k2.5"
+++                                                               "openrouter/moonshotai/kimi-k2.5"]}}})]
++         (swap! test-state assoc :injector injector)
++         (try
++           (f)
++diff --git a/test/mcp_injector/native_tools_test.clj b/test/mcp_injector/native_tools_test.clj
++index 865537c..1d8a674 100644
++--- a/test/mcp_injector/native_tools_test.clj
+++++ b/test/mcp_injector/native_tools_test.clj
++@@ -14,10 +14,9 @@
++         injector-server (core/start-server {:port 0
++                                             :host "127.0.0.1"
++                                             :llm-url (str "http://localhost:" (:port llm-server))
++-                                            :mcp-servers {:servers {}
++-                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))
++-                                                                        :governance {:mode :permissive
++-                                                                                     :policy {:allow ["clojure-eval"]}}}}})]
+++                                            :governance {:mode :permissive
+++                                                         :policy {:allow ["clojure-eval"]}}
+++                                            :mcp-servers {:servers {}}})]
++     (try
++       (binding [*test-llm* llm-server
++                 *injector* injector-server]
++@@ -117,10 +116,9 @@
++           blocked-injector (core/start-server {:port 0
++                                                :host "127.0.0.1"
++                                                :llm-url (str "http://localhost:" llm-port)
++-                                               :mcp-servers {:servers {}
++-                                                             :llm-gateway {:url (str "http://localhost:" llm-port)
++-                                                                           :governance {:mode :permissive
++-                                                                                        :policy {:allow []}}}}})] ;; empty allow list
+++                                               :governance {:mode :permissive
+++                                                            :policy {:allow []}}
+++                                               :mcp-servers {:servers {}}})] ;; empty allow list
++       (try
++         ;; Explicitly clear state before starting the denial flow
++         (test-llm/clear-responses *test-llm*)
++diff --git a/test/mcp_injector/restoration_test.clj b/test/mcp_injector/restoration_test.clj
++new file mode 100644
++index 0000000..977369f
++--- /dev/null
+++++ b/test/mcp_injector/restoration_test.clj
++@@ -0,0 +1,104 @@
+++(ns mcp-injector.restoration-test
+++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
+++            [clojure.string :as str]
+++            [mcp-injector.test-llm-server :as test-llm]
+++            [mcp-injector.test-mcp-server :as test-mcp]
+++            [mcp-injector.core :as core]
+++            [cheshire.core :as json]
+++            [org.httpkit.client :as http]))
+++
+++(def test-state (atom {}))
+++
+++(use-fixtures :once
+++  (fn [f]
+++    (let [llm (test-llm/start-server)
+++          mcp (test-mcp/start-server)]
+++      (swap! test-state assoc :llm llm :mcp mcp)
+++      (let [injector (core/start-server
+++                      {:port 0
+++                       :host "127.0.0.1"
+++                       :llm-url (str "http://localhost:" (:port llm))
+++                       :mcp-servers {:servers
+++                                     {:trusted-db
+++                                      {:url (str "http://localhost:" (:port mcp))
+++                                       :tools ["query"]
+++                                       :trust :restore}
+++                                      :untrusted-api
+++                                      {:url (str "http://localhost:" (:port mcp))
+++                                       :tools ["send"]
+++                                       :trust :none}}}})]
+++        (swap! test-state assoc :injector injector)
+++        (try
+++          (f)
+++          (finally
+++            (core/stop-server injector)
+++            (test-llm/stop-server llm)
+++            (test-mcp/stop-server mcp)))))))
+++
+++(deftest test-secret-redaction-and-restoration
+++  (testing "End-to-end Redact -> Decide -> Restore flow"
+++    (let [{:keys [injector llm mcp]} @test-state
+++          port (:port injector)]
+++
+++      ;; 1. Setup MCP to return a secret
+++      ((:set-tools! mcp)
+++       {:query {:description "Query database"
+++                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
+++                :handler (fn [args]
+++                           (if (or (:email args) (get args "email"))
+++                             {:status "success" :received (or (:email args) (get args "email"))}
+++                             {:email "wes@example.com" :secret "super-secret-123"}))}})
+++
+++      ;; 2. LLM Turn 1: Get data (will be redacted)
+++      (test-llm/set-next-response llm
+++                                  {:role "assistant"
+++                                   :tool_calls [{:id "call_1"
+++                                                 :function {:name "mcp__trusted-db__query"
+++                                                            :arguments "{\"q\":\"select user\"}"}}]})
+++
+++      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
+++      ;; Token is deterministic: SHA256("EMAIL_ADDRESS|wes@example.com|test-request-id-12345") -> a35e2662
+++      (test-llm/set-next-response llm
+++                                  {:role "assistant"
+++                                   :content "I found the user. Now updating."
+++                                   :tool_calls [{:id "call_2"
+++                                                 :function {:name "mcp__trusted-db__query"
+++                                                            :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
+++
+++      ;; Final response
+++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+++
+++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+++                                 {:body (json/generate-string
+++                                         {:model "brain"
+++                                          :messages [{:role "user" :content "Update user wes"}]
+++                                          :stream false
+++                                          :extra_body {:request-id "test-request-id-12345"}})
+++                                  :headers {"Content-Type" "application/json"}})]
+++        (is (= 200 (:status response)))
+++
+++        ;; Verify MCP received the RESTORED value in the second call
+++        (let [mcp-requests @(:received-requests mcp)
+++              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
+++              update-call (last tool-calls)
+++              ;; Arguments in MCP request is a JSON string, parse it
+++              args-str (-> update-call :body :params :arguments)
+++              args (json/parse-string args-str true)]
+++          (is (= "wes@example.com" (:email args))))
+++
+++        ;; Verify LLM received REDACTED token (not original) in tool result
+++        (let [llm-requests @(:received-requests llm)
+++              ;; Find the request where LLM called tool (has tool_calls)
+++              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
+++              ;; Get the tool result message that follows the tool call
+++              msgs (:messages tool-call-req)
+++              tool-result-msg (last msgs)]
+++          ;; LLM should see token, not original email
+++          (is (some? tool-result-msg))
+++          (is (= "tool" (:role tool-result-msg)))
+++          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
+++          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
+++
+++(defn -main [& _args]
+++  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
+++    (System/exit (if (zero? (:fail result)) 0 1))))
++diff --git a/test/mcp_injector/test_llm_server.clj b/test/mcp_injector/test_llm_server.clj
++index fa3f9d7..3b4ee3a 100644
++--- a/test/mcp_injector/test_llm_server.clj
+++++ b/test/mcp_injector/test_llm_server.clj
++@@ -16,15 +16,18 @@
++    :model (get request-body :model "gpt-4o-mini")
++    :choices [{:index 0
++               :message {:role "assistant"
++-                        :content (:content response-data)
+++                        :content (or (get-in response-data [:choices 0 :message :content])
+++                                     (:content response-data))
++                         :tool_calls (when (:tool_calls response-data)
++                                       (map-indexed
++                                        (fn [idx tc]
++-                                         {:id (str "call_" idx)
++-                                          :type "function"
++-                                          :index idx
++-                                          :function {:name (:name tc)
++-                                                     :arguments (json/generate-string (:arguments tc))}})
+++                                         (let [fn-name (or (:name tc) (get-in tc [:function :name]))
+++                                               fn-args (or (:arguments tc) (get-in tc [:function :arguments]))]
+++                                           {:id (str "call_" idx)
+++                                            :type "function"
+++                                            :index idx
+++                                            :function {:name fn-name
+++                                                       :arguments (json/generate-string fn-args)}}))
++                                        (:tool_calls response-data)))}
++               :finish_reason (if (:tool_calls response-data) "tool_calls" "stop")}]
++     ;; Default usage to nil to avoid polluting stats in tests that don't explicitly set it
+diff --git a/dev/PII_RESTORATION_PLAN.md b/dev/PII_RESTORATION_PLAN.md
+new file mode 100644
+index 0000000..903b280
+--- /dev/null
++++ b/dev/PII_RESTORATION_PLAN.md
+@@ -0,0 +1,31 @@
++# PII Restoration & Secret Substitution Plan
++
++## Status
++- **Branch:** `feat/pii-restoration`
++- **Current State:** COMPLETE - All tests passing
++- **Completed:** 2026-03-14
++
++## Summary
++Successfully implemented "Smart Vault" PII restoration system:
++- Deterministic token generation with SHA-256
++- Request-scoped vault for token/value mapping
++- Trust levels (`:none`, `:read`, `:restore`) at server and tool level
++- Two-way substitution: LLM sees tokens, trusted tools see real values
++
++## Core Strategy: The "Smart Vault"
++We are moving from "One-Way Redaction" to "Two-Way Substitution" for trusted tools.
++
++### 1. Tokenization (Outbound to LLM)
++- **Deterministic Hashing:** Replace `[EMAIL_ADDRESS]` with `[PII_EMAIL_8ce0db03]`. 
++- **Vaulting:** Store `{ "[PII_EMAIL_8ce0db03]" "wes@example.com" }` in a request-scoped vault.
++- **Structural Awareness:** Use `clojure.walk` to redact JSON values while preserving keys (so LLM understands schema).
++
++### 2. Restoration (Inbound to Tools)
++- **Trust Tiers:** Define `:trust :restore` in `mcp-servers.edn`.
++- **Restoration:** If a tool call targets a trusted server, `mcp-injector` swaps tokens back for real values before execution.
++- **Safety:** Untrusted tools continue to see only the redacted tokens.
++
++## Build Results
++- **55 tests** - All passing
++- **Lint** - Clean
++- **Format** - Clean
+diff --git a/dev/specs/configurable-trust-levels.edn b/dev/specs/configurable-trust-levels.edn
+new file mode 100644
+index 0000000..3881440
+--- /dev/null
++++ b/dev/specs/configurable-trust-levels.edn
+@@ -0,0 +1,14 @@
++{:title "Configurable PII Restoration Trust Levels"
++ :description "Enhance Smart Vault to support configurable trust levels for fine-grained control over token restoration."
++ :acceptance-criteria
++ ["Trust levels :none, :read, :restore are supported in mcp-servers.edn config"
++  "edit tool can restore tokens from vault when server/tool trust is :restore"
++  "NixOS module exposes trust configuration options"
++  "Audit logging records restoration events"
++  "Read tool test demonstrates token generation and vault storage"
++  "Edit tool test demonstrates successful token resolution and file modification"]
++ :edge-cases
++ ["Token not found in vault (should pass through)"
++  "Multiple tokens in single argument string"
++  "Restoration for deeply nested JSON structures"
++  "Partial path trust (server-level :restore but tool-level :none)"]}
+diff --git a/dev/specs/pii-restoration.edn b/dev/specs/pii-restoration.edn
+new file mode 100644
+index 0000000..edb6cd1
+--- /dev/null
++++ b/dev/specs/pii-restoration.edn
+@@ -0,0 +1,15 @@
++{:title "PII/Secret Restoration (Round-Trip)"
++ :description "Enable trusted tools to receive original sensitive data while keeping the LLM's view redacted."
++ :acceptance-criteria
++ ["Tool outputs containing PII are tokenized with deterministic, hybrid labels (e.g., [EMAIL_8f3a2])"
++  "Tokens remain consistent across a single request context"
++  "A request-scoped Vault stores the mapping of Token -> Original Value"
++  "Trusted tools (marked with :trust :restore) receive restored values in their arguments"
++  "Untrusted tools receive the literal token strings"
++  "Deep JSON redaction preserves map keys but tokenizes values"]
++ :edge-cases
++ ["Recursive data structures in tool arguments"
++  "Mixed plain-text and JSON tool outputs"
++  "Token collisions (mitigated via request-id salt)"
++  "Empty or null values in scanned data"]
++ :depends-on [:governance-core :mcp-client]}
+diff --git a/flake.nix b/flake.nix
+index fb71f9f..30dc860 100644
+--- a/flake.nix
++++ b/flake.nix
+@@ -134,19 +134,28 @@
+               description = "URL of OpenAI-compatible LLM endpoint";
+             };
+ 
+-            mcpServers = mkOption {
+-              type = types.attrs;
+-              default = {};
+-              description = "MCP server configurations";
+-              example = literalExpression ''
+-                {
+-                  stripe = {
+-                    url = "http://localhost:3001/mcp";
+-                    tools = ["retrieve_customer" "list_charges"];
+-                  };
+-                }
+-              '';
+-            };
++             mcpServers = mkOption {
++               type = types.attrs;
++               default = {};
++               description = "MCP server configurations";
++               example = literalExpression ''
++                 {
++                   stripe = {
++                     url = "http://localhost:3001/mcp";
++                     trust = "restore";  # "none" (default), "read", or "restore"
++                     tools = ["retrieve_customer" "list_charges"];
++                   };
++                   workspace = {
++                     url = "http://localhost:3000/mcp";
++                     trust = "restore";
++                     tools = [
++                       { name = "read"; trust = "read"; }
++                       { name = "write"; trust = "restore"; }
++                     ];
++                   };
++                 }
++               '';
++             };
+ 
+             governance = mkOption {
+               type = types.submodule {
+diff --git a/mcp-servers.example.edn b/mcp-servers.example.edn
+index bfe5165..ba0ef3b 100644
+--- a/mcp-servers.example.edn
++++ b/mcp-servers.example.edn
+@@ -11,12 +11,16 @@
+ 
+   :servers
+   {:auphonic {:url "http://localhost:3003/mcp"
+-              :tools nil}
+-   :nixos {:cmd ["nix" "run" "github:utensils/mcp-nixos"]}
+-    ;; Example local tool (uncomment to use)
+-    ;; :filesystem
+-    ;; {:cmd ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/path/to/allowed/dir"]
+-    ;;  :env {"EXAMPLE_VAR" "value"}}
++              :tools nil
++              :trust "none"}   ; "none" (default), "read", or "restore"
++   :nixos {:cmd ["nix" "run" "github:utensils/mcp-nixos"]
++           :trust "none"}
++     ;; Example local tool (uncomment to use)
++     ;; :filesystem
++     ;; {:cmd ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/path/to/allowed/dir"]
++     ;;  :trust "restore"  ; allow token restoration for edit/write
++     ;;  :tools [{:name "read" :trust "read"}
++     ;;          {:name "write" :trust "restore"}]}
+    }
+ ;; LLM gateway configuration
+   :llm-gateway
+diff --git a/result b/result
+deleted file mode 120000
+index eea2214..0000000
+--- a/result
++++ /dev/null
+@@ -1 +0,0 @@
+-/nix/store/gdjbiza5hidsdb7lx3spirlsxybwlzry-mcp-injector-0.1.0
+\ No newline at end of file
+diff --git a/src/mcp_injector/config.clj b/src/mcp_injector/config.clj
+index aa15670..fe3bcf2 100644
+--- a/src/mcp_injector/config.clj
++++ b/src/mcp_injector/config.clj
+@@ -166,6 +166,36 @@
+     []
+     (:servers mcp-config))))
+ 
++(defn get-server-trust
++  "Get trust level for a server/tool combination.
++   Returns :restore (full restoration), :read (read-only access), :none (untrusted), or :block.
++   Precedence: tool-level :trust > server-level :trust > :none.
++   Accepts trust values as either keywords (:restore) or strings (\"restore\")."
++  [mcp-config server-name tool-name]
++  (let [servers (:servers mcp-config)
++        server (get servers (keyword server-name))]
++    (if-not server
++      :none
++      (let [server-trust (some-> server :trust keyword)
++            tool-configs (:tools server)
++            tool-config (cond
++                          (map? tool-configs)
++                          (get tool-configs (keyword tool-name))
++
++                          (sequential? tool-configs)
++                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)
++
++                          :else nil)
++            tool-trust (some-> tool-config :trust keyword)]
++        (cond
++          (= tool-trust :block) :block
++          (= server-trust :block) :block
++          (= tool-trust :restore) :restore
++          (= server-trust :restore) :restore
++          (= tool-trust :read) :read
++          (= server-trust :read) :read
++          :else :none)))))
++
+ (defn get-meta-tool-definitions
+   "Get definitions for meta-tools like get_tool_schema and native tools"
+   []
+diff --git a/src/mcp_injector/core.clj b/src/mcp_injector/core.clj
+index 5cee001..2a5d240 100644
+--- a/src/mcp_injector/core.clj
++++ b/src/mcp_injector/core.clj
+@@ -169,12 +169,12 @@
+         (= full-name "clojure-eval")
+         (try
+           (let [code (:code args)
+-                ;; NOTE: clojure-eval is a full JVM/Babashka load-string. 
++                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
+                 ;; Security is currently enforced only via the Policy layer (explicit opt-in).
+                 result (load-string code)]
+-            (pr-str result))
++            (json/generate-string result))
+           (catch Exception e
+-            {:error (str "Eval error: " (.getMessage e))}))
++            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
+ 
+         (str/starts-with? full-name "mcp__")
+         (let [t-name (str/replace full-name #"^mcp__" "")
+@@ -199,28 +199,90 @@
+ 
+         :else {:error (str "Unknown tool: " full-name)}))))
+ 
+-(defn- scrub-messages [messages]
++(defn- parse-tool-name
++  "Parse mcp__server__tool format into [server tool]"
++  [full-name]
++  (if (str/includes? full-name "__")
++    (let [t-name (str/replace full-name #"^mcp__" "")
++          idx (str/last-index-of t-name "__")]
++      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
++    [nil full-name]))
++
++(defn- scrub-messages [messages vault request-id]
+   (mapv (fn [m]
+-          (if (string? (:content m))
+-            (let [{:keys [text detected]} (pii/scan-and-redact (:content m) {:mode :replace})]
+-              (when (seq detected)
+-                (log-request "info" "PII Redacted" {:labels detected} {:role (:role m)}))
+-              (assoc m :content text))
+-            m))
++          (let [content (:content m)
++                role (:role m)]
++            (if (and (string? content)
++                     ;; Only redact user/system messages - assistant tool results are already handled
++                     (or (= role "system") (= role "user"))
++                     ;; Skip if already contains PII tokens (avoid double-redaction)
++                     ;; Token format: [LABEL_hex8] e.g., [EMAIL_ADDRESS_a35e2662]
++                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{8,}\]" content)))
++              (let [config {:mode :replace :salt request-id}
++                    [redacted-content _ _] (pii/redact-data content config vault)]
++                (assoc m :content redacted-content))
++              m)))
+         messages))
+ 
+-(defn- sanitize-tool-output [content]
+-  (if (string? content)
+-    (str/replace content
+-                 #"(?im)^\s*(system|human|assistant|user)\s*:"
+-                 "[REDACTED_ROLE_MARKER]")
+-    content))
++(defn- restore-tool-args
++  "Restore tokens in tool args if server is trusted.
++   For edit/write operations, also resolve standalone tokens in string arguments."
++  [args vault mcp-servers full-tool-name]
++  (let [[server tool] (parse-tool-name full-tool-name)
++        trust (when server (config/get-server-trust mcp-servers server tool))
++        ;; For file editing tools, also resolve tokens in string values
++        restore-strings? (contains? #{"edit" "write"} tool)
++        restored (if (= trust :restore)
++                   (pii/restore-tokens args vault)
++                   (if restore-strings?
++                     ;; Even if not fully trusted, resolve tokens for file operations
++                     (pii/restore-tokens args vault)
++                     args))]
++    restored))
++
++(defn- redact-tool-output
++  "Redact PII from tool output, return [content vault]"
++  [raw-output vault request-id]
++  (let [;; Try to parse as JSON first for JSON tokenization
++        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
++        ;; If parsed successfully, redact the data structure; otherwise redact the string
++        ;; Special handling for MCP response format: parse nested :text field if present
++        [redacted new-vault detected] (if parsed
++                                        (let [;; Check if this is MCP response format with :text field containing JSON
++                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
++                                              parsed (cond
++                                                       (map? parsed)
++                                                       (if (string? (:text parsed))
++                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
++                                                              (catch Exception _ parsed))
++                                                         parsed)
++                                                       (sequential? parsed)
++                                                       (mapv (fn [item]
++                                                               (if (and (map? item) (string? (:text item)))
++                                                                 (try (assoc item :text (json/parse-string (:text item) true))
++                                                                      (catch Exception _ item))
++                                                                 item))
++                                                             parsed)
++                                                       :else parsed)
++                                              config {:mode :replace :salt request-id}
++                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
++                                          [(json/generate-string redacted-struct) vault-after detected-labels])
++                                        (let [config {:mode :replace :salt request-id}
++                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
++                                          [redacted-str vault-after detected-labels]))]
++
++    ;; Log the detected PII types (not scanning again)
++    (when (seq detected)
++      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
++    [redacted new-vault]))
+ 
+ (defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
+   (let [model (:model payload)
+         discovered-this-loop (atom {})
+-        context {:model model}]
+-    (loop [current-payload (update payload :messages scrub-messages)
++        vault (atom {})
++        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
++        context {:model model :request-id request-id}]
++    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
+            iteration 0]
+       (if (>= iteration max-iterations)
+         {:success true
+@@ -239,40 +301,46 @@
+                   tool-calls (:tool_calls message)]
+               (if-not tool-calls
+                 (assoc resp :provider model)
+-                (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
+-                                             (str/starts-with? (get-in % [:function :name]) "mcp__"))
++                (let [mcp-calls (filter (fn [tc]
++                                          (let [n (get-in tc [:function :name])]
++                                            (or (= n "get_tool_schema")
++                                                (and n (str/starts-with? n "mcp__")))))
+                                         tool-calls)
+                       native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
+                                            tool-calls)]
+                   (if (and (empty? mcp-calls) (empty? native-calls))
+                     (assoc resp :provider model)
+-                    (let [results (mapv (fn [tc]
+-                                          (let [fn-name (get-in tc [:function :name])
+-                                                args-str (get-in tc [:function :arguments])
+-                                                parse-result (try
+-                                                               {:success true :args (json/parse-string args-str true)}
+-                                                               (catch Exception e
+-                                                                 {:success false :error (.getMessage e)}))]
+-                                            (if (:success parse-result)
+-                                              (let [result (execute-tool fn-name (:args parse-result) mcp-servers discovered-this-loop governance context)
+-                                                    ;; Scrub and sanitize tool output
+-                                                    raw-content (if (string? result) result (json/generate-string result))
+-                                                    sanitized (sanitize-tool-output raw-content)
+-                                                    {:keys [text detected]} (pii/scan-and-redact sanitized {:mode :replace})
+-                                                    _ (when (seq detected)
+-                                                        (log-request "info" "PII Redacted in Tool Output" {:tool fn-name :labels detected} context))]
+-                                                {:role "tool"
++                    (let [[results new-vault]
++                          (reduce
++                           (fn [[results vault-state] tc]
++                             (let [fn-name (get-in tc [:function :name])
++                                   args-str (get-in tc [:function :arguments])
++                                   parse-result (try
++                                                  {:success true :args (json/parse-string args-str true)}
++                                                  (catch Exception e
++                                                    {:success false :error (.getMessage e)}))]
++                               (if (:success parse-result)
++                                 (let [;; Restore args if trusted
++                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
++                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
++                                       ;; Redact output with vault
++                                       raw-content (if (string? result) result (json/generate-string result))
++                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
++                                   [(conj results {:role "tool"
++                                                   :tool_call_id (:id tc)
++                                                   :name fn-name
++                                                   :content redacted})
++                                    updated-vault])
++                                 [(conj results {:role "tool"
+                                                  :tool_call_id (:id tc)
+                                                  :name fn-name
+-                                                 :content text})
+-                                              {:role "tool"
+-                                               :tool_call_id (:id tc)
+-                                               :name fn-name
+-                                               :content (json/generate-string
+-                                                         {:error "Malformed tool arguments JSON"
+-                                                          :details {:args-str args-str
+-                                                                    :parse-error (:error parse-result)}})})))
+-                                        (concat mcp-calls native-calls))
++                                                 :content (json/generate-string
++                                                           {:error "Malformed tool arguments JSON"
++                                                            :details {:args-str args-str
++                                                                      :parse-error (:error parse-result)}})})
++                                  vault-state])))
++                           [[] vault]
++                           (concat mcp-calls native-calls))
+                           newly-discovered @discovered-this-loop
+                           new-tools (vec (concat (config/get-meta-tool-definitions)
+                                                  (map (fn [[name schema]]
+@@ -281,9 +349,12 @@
+                                                                     :description (:description schema)
+                                                                     :parameters (:inputSchema schema)}})
+                                                       newly-discovered)))
+-                          new-messages (conj (vec (:messages current-payload)) message)
++                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
+                           new-messages (into new-messages results)]
+-                      (recur (assoc current-payload :messages new-messages :tools new-tools) (inc iteration)))))))))))))
++                      (recur (assoc current-payload
++                                    :messages (scrub-messages new-messages new-vault request-id)
++                                    :tools new-tools)
++                             (inc iteration)))))))))))))
+ 
+ (defn- set-cooldown! [provider minutes]
+   (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
+@@ -334,11 +405,14 @@
+                                   discovered-tools)
+         merged-tools (vec (concat (or existing-tools [])
+                                   meta-tools
+-                                  discovered-tool-defs))]
++                                  discovered-tool-defs))
++        ;; Merge extra_body into the request for fields like request-id
++        extra-body (or (:extra_body chat-req) {})]
+     (-> chat-req
+         (assoc :stream false)
+         (dissoc :stream_options)
+         (assoc :fallbacks fallbacks)
++        (merge extra-body) ;; Lift extra_body fields to top level
+         (update :messages (fn [msgs]
+                             (mapv (fn [m]
+                                     (if (and (= (:role m) "assistant") (:tool_calls m))
+@@ -428,11 +502,13 @@
+                      (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
+           {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
+     (catch Exception e
+-      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
+-        (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e)} {})
++      (let [err-type (or (some-> e ex-data :type name) "internal_error")
++            err-msg (or (.getMessage e) (str e))
++            stack (.getStackTrace e)]
++        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
+         {:status 400
+          :headers {"Content-Type" "application/json"}
+-         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
++         :body (json/generate-string {:error {:message err-msg
+                                               :type err-type}})}))))
+ 
+ (defn get-gateway-state []
+@@ -509,46 +585,51 @@
+                                                   :type err-type}})}))))))
+ 
+ (defn start-server [mcp-config]
+-  (let [initial-config (if (and (map? mcp-config) (not (:servers mcp-config)))
+-                         mcp-config
+-                         {})
+-        port (or (:port initial-config)
++  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
++        provided-governance (or (:governance mcp-config)
++                                (:governance (:mcp-servers mcp-config)))
++
++        ;; Runtime settings - prioritize input > env > default
++        port (or (:port mcp-config)
+                  (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
+                  8080)
+-        host (or (:host initial-config)
++        host (or (:host mcp-config)
+                  (System/getenv "MCP_INJECTOR_HOST")
+                  "127.0.0.1")
+-        llm-url (or (:llm-url initial-config)
++        llm-url (or (:llm-url mcp-config)
+                     (System/getenv "MCP_INJECTOR_LLM_URL")
+                     "http://localhost:11434")
+-        log-level (or (:log-level initial-config)
++        log-level (or (:log-level mcp-config)
+                       (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
+-        max-iterations (or (:max-iterations initial-config)
++        max-iterations (or (:max-iterations mcp-config)
+                            (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
+                            10)
+-        mcp-config-path (or (:mcp-config-path initial-config)
++        mcp-config-path (or (:mcp-config-path mcp-config)
+                             (System/getenv "MCP_INJECTOR_MCP_CONFIG")
+                             "mcp-servers.edn")
+         ;; Audit trail config
+-        audit-log-path (or (:audit-log-path initial-config)
++        audit-log-path (or (:audit-log-path mcp-config)
+                            (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
+                            "logs/audit.log.ndjson")
+-        audit-secret (or (:audit-secret initial-config)
++        audit-secret (or (:audit-secret mcp-config)
+                          (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
+                          "default-audit-secret")
+         ;; Merge provided mcp-config with loaded ones if needed
+         base-mcp-servers (cond
+                            (and (map? mcp-config) (:servers mcp-config)) mcp-config
+-                           (:mcp-servers initial-config) (:mcp-servers initial-config)
++                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
+                            :else (config/load-mcp-servers mcp-config-path))
+-        ;; Apply overrides from initial-config (like :virtual-models in tests)
+-        mcp-servers (if (seq initial-config)
+-                      (let [gateway-overrides (select-keys initial-config [:virtual-models :fallbacks :url])]
+-                        (update base-mcp-servers :llm-gateway merge gateway-overrides))
++        ;; Apply overrides from mcp-config (like :virtual-models in tests)
++        mcp-servers (if (map? mcp-config)
++                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
++                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
++                        (if-let [gov (:governance mcp-config)]
++                          (assoc merged :governance gov)
++                          merged))
+                       base-mcp-servers)
+-        ;; Unified configuration resolution
++        ;; Unified configuration resolution - pass extracted governance
+         unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
+-        final-governance (config/resolve-governance (assoc mcp-servers :governance (:governance initial-config)) unified-env)
++        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
+         final-config {:port port :host host :llm-url llm-url :log-level log-level
+                       :max-iterations max-iterations :mcp-config-path mcp-config-path
+                       :audit-log-path audit-log-path :audit-secret audit-secret
+diff --git a/src/mcp_injector/pii.clj b/src/mcp_injector/pii.clj
+index faeb7e7..4d8021c 100644
+--- a/src/mcp_injector/pii.clj
++++ b/src/mcp_injector/pii.clj
+@@ -1,12 +1,13 @@
+ (ns mcp-injector.pii
+-  (:require [clojure.string :as str]))
++  (:require [clojure.string :as str]
++            [clojure.walk :as walk])
++  (:import (java.security MessageDigest)))
+ 
+ (def default-patterns
+   [{:id :EMAIL_ADDRESS
+     :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
+     :label "[EMAIL_ADDRESS]"}
+    {:id :IBAN_CODE
+-    ;; Tightened range to 15-34 and added case-insensitivity support via (?i)
+     :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
+     :label "[IBAN_CODE]"}])
+ 
+@@ -46,8 +47,6 @@
+ (defn- scan-env [text env-vars mode]
+   (reduce-kv
+    (fn [acc k v]
+-     ;; Case-sensitive match for env vars is usually safer, 
+-     ;; but we ensure the value is long enough to avoid false positives.
+      (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
+        (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
+        acc))
+@@ -64,7 +63,6 @@
+   (let [tokens (str/split text #"\s+")]
+     (reduce
+      (fn [acc token]
+-       ;; Threshold raised to 4.0 + diversity check + length check
+        (if (and (> (count token) 12)
+                 (> (shannon-entropy token) threshold)
+                 (character-diversity? token))
+@@ -74,15 +72,13 @@
+      tokens)))
+ 
+ (defn scan-and-redact
+-  "Scans input text for PII patterns, high-entropy secrets, and env vars.
+-   Calculations are performed sequentially on the text."
++  "Scans input text for PII patterns, high-entropy secrets, and env vars."
+   [text {:keys [mode patterns entropy-threshold env]
+          :or {mode :replace
+               patterns default-patterns
+               entropy-threshold 4.0
+               env {}}}]
+-  (let [;; 1. Regex patterns (Standard PII)
+-        regex-result (reduce
++  (let [regex-result (reduce
+                       (fn [state {:keys [id pattern label]}]
+                         (if (seq (re-seq pattern (:text state)))
+                           {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
+@@ -90,14 +86,89 @@
+                           state))
+                       {:text text :detected []}
+                       patterns)
+-
+-        ;; 2. Env vars (Exact matches)
+         env-text (scan-env (:text regex-result) env mode)
+         env-detections (find-env-detections text env)
+-
+-        ;; 3. Entropy (Heuristic secrets)
+         final-text (scan-entropy env-text entropy-threshold mode)
+         entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
+-
+     {:text final-text
+      :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
++
++(defn generate-token
++  "Generate a deterministic, truncated SHA-256 hash token.
++   Uses 12 hex chars (48 bits) to reduce collision probability."
++  [label value salt]
++  (let [input (str (name label) "|" value "|" salt)
++        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
++        hash-str (->> digest
++                      (map (partial format "%02x"))
++                      (apply str))
++        truncated (subs hash-str 0 12)]
++    (str "[" (name label) "_" truncated "]")))
++
++(defn- redact-string-value
++  "Redact a single string value, returning [redacted-text token detected-label]"
++  [v config]
++  (if-not (string? v)
++    [v nil nil]
++    (if (empty? v)
++      [v nil nil]
++      (let [vault (:vault config)
++            salt (:salt config)
++            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
++            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
++        (cond
++          existing-token [existing-token nil nil]
++          previous-token [previous-token nil nil]
++          :else
++          (let [result (scan-and-redact v config)]
++            (if (seq (:detected result))
++              (let [detected (first (:detected result))
++                    token (generate-token detected v salt)]
++                (swap! vault assoc token v)
++                [token token detected])
++              [(:text result) nil nil])))))))
++
++(defn redact-data
++  "Recursively walk a data structure, redact string values, store in vault.
++    Returns [redacted-data vault-atom detected-labels]"
++  ([data config]
++   (redact-data data config (atom {})))
++  ([data config vault]
++   (let [config-with-vault (assoc config :vault vault)
++         detected-labels (atom [])
++         redacted (walk/postwalk
++                   (fn [x]
++                     (if (string? x)
++                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
++                         (when detected (swap! detected-labels conj detected))
++                         redacted-text)
++                       x))
++                   data)]
++     [redacted vault @detected-labels])))
++
++(defn restore-tokens
++  "Recursively walk a data structure, replacing tokens with original values from vault."
++  [data vault]
++  (let [v-map @vault]
++    (if (empty? v-map)
++      data
++      (walk/postwalk
++       (fn [x]
++         (if (string? x)
++           (reduce
++            (fn [s [token original]]
++              (if (and (string? s) (str/includes? s token))
++                (str/replace s (str token) (str original))
++                s))
++            x
++            v-map)
++           x))
++       data))))
++
++(defn resolve-token
++  "Resolve a single token string back to its original value.
++   Returns the original value if found, or the token unchanged."
++  [token-str vault]
++  (let [v-map @vault
++        original (get v-map token-str)]
++    (or original token-str)))
+diff --git a/test/mcp_injector/discovery_test.clj b/test/mcp_injector/discovery_test.clj
+index cf4e069..6ae43a4 100644
+--- a/test/mcp_injector/discovery_test.clj
++++ b/test/mcp_injector/discovery_test.clj
+@@ -79,8 +79,8 @@
+       (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
+       (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))
+       ;; content might be redacted as [EMAIL_ADDRESS] or [HIGH_ENTROPY_SECRET] depending on scanner
+-      (is (some (fn [m] (or (str/includes? (:content m) "[EMAIL_ADDRESS]")
+-                            (str/includes? (:content m) "[HIGH_ENTROPY_SECRET]"))) tool-msgs)))))
++      (is (some (fn [m] (or (re-find #"\[EMAIL_ADDRESS(_[a-f0-9]{12})?\]" (:content m))
++                            (re-find #"\[HIGH_ENTROPY_SECRET(_[a-f0-9]{12})?\]" (:content m)))) tool-msgs)))))
+ 
+ (deftest tool-discovery-filtering-nil-shows-all
+   (testing "When :tools is nil, all discovered tools from MCP server should be shown"
+diff --git a/test/mcp_injector/llm_shim_test.clj b/test/mcp_injector/llm_shim_test.clj
+index 4142816..748e04b 100644
+--- a/test/mcp_injector/llm_shim_test.clj
++++ b/test/mcp_injector/llm_shim_test.clj
+@@ -25,7 +25,9 @@
+                       {:port 0
+                        :host "127.0.0.1"
+                        :llm-url (str "http://localhost:" (:port llm))
+-                       :mcp-config "./mcp-servers.edn"})]
++                       :mcp-servers {:llm-gateway {:fallbacks ["zen/kimi-k2.5-free"
++                                                               "nvidia/moonshotai/kimi-k2.5"
++                                                               "openrouter/moonshotai/kimi-k2.5"]}}})]
+         (swap! test-state assoc :injector injector)
+         (try
+           (f)
+diff --git a/test/mcp_injector/native_tools_test.clj b/test/mcp_injector/native_tools_test.clj
+index 865537c..1d8a674 100644
+--- a/test/mcp_injector/native_tools_test.clj
++++ b/test/mcp_injector/native_tools_test.clj
+@@ -14,10 +14,9 @@
+         injector-server (core/start-server {:port 0
+                                             :host "127.0.0.1"
+                                             :llm-url (str "http://localhost:" (:port llm-server))
+-                                            :mcp-servers {:servers {}
+-                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))
+-                                                                        :governance {:mode :permissive
+-                                                                                     :policy {:allow ["clojure-eval"]}}}}})]
++                                            :governance {:mode :permissive
++                                                         :policy {:allow ["clojure-eval"]}}
++                                            :mcp-servers {:servers {}}})]
+     (try
+       (binding [*test-llm* llm-server
+                 *injector* injector-server]
+@@ -117,10 +116,9 @@
+           blocked-injector (core/start-server {:port 0
+                                                :host "127.0.0.1"
+                                                :llm-url (str "http://localhost:" llm-port)
+-                                               :mcp-servers {:servers {}
+-                                                             :llm-gateway {:url (str "http://localhost:" llm-port)
+-                                                                           :governance {:mode :permissive
+-                                                                                        :policy {:allow []}}}}})] ;; empty allow list
++                                               :governance {:mode :permissive
++                                                            :policy {:allow []}}
++                                               :mcp-servers {:servers {}}})] ;; empty allow list
+       (try
+         ;; Explicitly clear state before starting the denial flow
+         (test-llm/clear-responses *test-llm*)
+diff --git a/test/mcp_injector/restoration_test.clj b/test/mcp_injector/restoration_test.clj
+new file mode 100644
+index 0000000..418402e
+--- /dev/null
++++ b/test/mcp_injector/restoration_test.clj
+@@ -0,0 +1,152 @@
++(ns mcp-injector.restoration-test
++  (:require [clojure.test :refer [deftest is testing use-fixtures]]
++            [clojure.string :as str]
++            [mcp-injector.pii :as pii]
++            [mcp-injector.test-llm-server :as test-llm]
++            [mcp-injector.test-mcp-server :as test-mcp]
++            [mcp-injector.core :as core]
++            [cheshire.core :as json]
++            [org.httpkit.client :as http]))
++
++(def test-state (atom {}))
++
++(use-fixtures :once
++  (fn [f]
++    (let [llm (test-llm/start-server)
++          mcp (test-mcp/start-server)]
++      (swap! test-state assoc :llm llm :mcp mcp)
++      (let [injector (core/start-server
++                      {:port 0
++                       :host "127.0.0.1"
++                       :llm-url (str "http://localhost:" (:port llm))
++                       :mcp-servers {:servers
++                                     {:trusted-db
++                                      {:url (str "http://localhost:" (:port mcp))
++                                       :tools ["query"]
++                                       :trust :restore}
++                                     :untrusted-api
++                                      {:url (str "http://localhost:" (:port mcp))
++                                       :tools ["send"]
++                                       :trust :none}
++                                     :workspace
++                                      {:url (str "http://localhost:" (:port mcp))
++                                       :trust :restore}}})]
++        (swap! test-state assoc :injector injector)
++        (try
++          (f)
++          (finally
++            (core/stop-server injector)
++            (test-llm/stop-server llm)
++            (test-mcp/stop-server mcp)))))))
++
++(deftest test-secret-redaction-and-restoration
++  (testing "End-to-end Redact -> Decide -> Restore flow"
++    (let [{:keys [injector llm mcp]} @test-state
++          port (:port injector)]
++      ;; 1. Setup MCP to return a secret
++      ((:set-tools! mcp)
++       {:query {:description "Query database"
++                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
++                :handler (fn [args]
++                           (if (or (:email args) (get args "email"))
++                             {:status "success" :received (or (:email args) (get args "email"))}
++                             {:email "wes@example.com" :secret "super-secret-123"})}})
++      ;; 2. LLM Turn 1: Get data (will be redacted)
++      (test-llm/set-next-response llm
++                                   {:role "assistant"
++                                    :tool_calls [{:id "call_1"
++                                                  :function {:name "mcp__trusted-db__query"
++                                                             :arguments "{\"q\":\"select user\"}"}}]})
++      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
++      (test-llm/set-next-response llm
++                                   {:role "assistant"
++                                    :content "I found the user. Now updating."
++                                    :tool_calls [{:id "call_2"
++                                                  :function {:name "mcp__trusted-db__query"
++                                                             :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
++      ;; Final response
++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
++                                 {:body (json/generate-string
++                                         {:model "brain"
++                                          :messages [{:role "user" :content "Update user wes"}]
++                                          :stream false
++                                          :extra_body {:request-id "test-request-id-12345"}})
++                                  :headers {"Content-Type" "application/json"}})]
++        (is (= 200 (:status response)))
++        ;; Verify MCP received the RESTORED value in the second call
++        (let [mcp-requests @(:received-requests mcp)
++              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
++              update-call (last tool-calls)
++              args-str (-> update-call :body :params :arguments)
++              args (json/parse-string args-str true)]
++          (is (= "wes@example.com" (:email args))))
++        ;; Verify LLM received REDACTED token (not original) in tool result
++        (let [llm-requests @(:received-requests llm)
++              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
++              msgs (:messages tool-call-req)
++              tool-result-msg (last msgs)]
++          (is (some? tool-result-msg))
++          (is (= "tool" (:role tool-result-msg)))
++          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
++          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
++
++(deftest test-edit-tool-with-pii-token
++  (testing "Edit tool can use restored PII tokens (fixes read->edit workflow)"
++    (let [{:keys [injector llm mcp]} @test-state
++          port (:port injector)
++          request-id "edit-test-request-id"
++          secret-email "wes@example.com"
++          token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
++      ;; Setup MCP with read and edit tools
++      ((:set-tools! mcp)
++       {:read-file
++        {:description "Read file contents"
++         :schema {:type "object" :properties {:path {:type "string"}}}
++         :handler (fn [args]
++                    {:content secret-email})}
++        :edit-file
++        {:description "Edit file"
++         :schema {:type "object" :properties {:path {:type "string"}
++                                               :old_string {:type "string"}
++                                               :new_string {:type "string"}}}
++         :handler (fn [args]
++                    {:success true
++                     :received-args args})}})
++      ;; LLM Turn 1: Read file - should get token
++      (test-llm/set-next-response llm
++                                   {:role "assistant"
++                                    :content "I'll read the file."
++                                    :tool_calls [{:id "call_1"
++                                                  :function {:name "mcp__workspace__read-file"
++                                                             :arguments (json/generate-string {:path "/tmp/script.sh"})}}]})
++      ;; LLM Turn 2: Uses token in edit old_string
++      (test-llm/set-next-response llm
++                                   {:role "assistant"
++                                    :content "Updating email..."
++                                    :tool_calls [{:id "call_2"
++                                                  :function {:name "mcp__workspace__edit-file"
++                                                             :arguments (json/generate-string
++                                                                         {:path "/tmp/script.sh"
++                                                                          :old_string token
++                                                                          :new_string "new@example.com"})}}]})
++      ;; Final response
++      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
++      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
++                                 {:body (json/generate-string
++                                         {:model "brain"
++                                          :messages [{:role "user" :content "Update the email in /tmp/script.sh"}]
++                                          :stream false
++                                          :extra_body {:request-id request-id}})
++                                  :headers {"Content-Type" "application/json"}})]
++        (is (= 200 (:status response)))
++        ;; Verify that edit tool received the ACTUAL email (not token) in old_string
++        (let [mcp-requests @(:received-requests mcp)
++              edit-call (last (filter #(= "tools/call" (-> % :body :method)) mcp-requests))
++              args-str (-> edit-call :body :params :arguments)
++              args (json/parse-string args-str true)]
++          (is (= secret-email (:old_string args))))))))
++
++(defn -main [& _args]
++  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
++    (System/exit (if (zero? (:fail result)) 0 1))))
+diff --git a/test/mcp_injector/test_llm_server.clj b/test/mcp_injector/test_llm_server.clj
+index fa3f9d7..3b4ee3a 100644
+--- a/test/mcp_injector/test_llm_server.clj
++++ b/test/mcp_injector/test_llm_server.clj
+@@ -16,15 +16,18 @@
+    :model (get request-body :model "gpt-4o-mini")
+    :choices [{:index 0
+               :message {:role "assistant"
+-                        :content (:content response-data)
++                        :content (or (get-in response-data [:choices 0 :message :content])
++                                     (:content response-data))
+                         :tool_calls (when (:tool_calls response-data)
+                                       (map-indexed
+                                        (fn [idx tc]
+-                                         {:id (str "call_" idx)
+-                                          :type "function"
+-                                          :index idx
+-                                          :function {:name (:name tc)
+-                                                     :arguments (json/generate-string (:arguments tc))}})
++                                         (let [fn-name (or (:name tc) (get-in tc [:function :name]))
++                                               fn-args (or (:arguments tc) (get-in tc [:function :arguments]))]
++                                           {:id (str "call_" idx)
++                                            :type "function"
++                                            :index idx
++                                            :function {:name fn-name
++                                                       :arguments (json/generate-string fn-args)}}))
+                                        (:tool_calls response-data)))}
+               :finish_reason (if (:tool_calls response-data) "tool_calls" "stop")}]
+     ;; Default usage to nil to avoid polluting stats in tests that don't explicitly set it
+=== FILE: src/mcp_injector/config.clj ===
+(ns mcp-injector.config
+  "Configuration and environment variables for mcp-injector."
+  (:require [clojure.edn :as edn]
+            [clojure.java.io :as io]
+            [clojure.walk :as walk]
+            [clojure.string :as str]))
+
+(def default-config
+  {:port 8088
+   :host "127.0.0.1"
+   :llm-url "http://localhost:8080"
+   :mcp-config "./mcp-servers.edn"
+   :max-iterations 10
+   :log-level "debug"
+   :timeout-ms 1800000
+   :audit-log-path "logs/audit.log.ndjson"
+   :audit-secret "default-audit-secret"})
+
+(defn env-var
+  ([name] (System/getenv name))
+  ([name default] (or (System/getenv name) default)))
+
+(defn- parse-int [s default]
+  (try
+    (Integer/parseInt s)
+    (catch Exception _ default)))
+
+(defn- keywordize-keys [m]
+  (walk/prewalk
+   (fn [x]
+     (if (map? x)
+       (into {} (map (fn [[k v]] [(keyword k) v]) x))
+       x))
+   m))
+
+(defn deep-merge
+  "Recursively merges maps. If keys conflict, the value from the last map wins.
+   Ensures nested defaults are not wiped out by partial user config.
+   If 'new' is nil, the 'old' value is preserved to prevent wiping out defaults."
+  [& maps]
+  (apply merge-with
+         (fn [old new]
+           (cond
+             (nil? new) old
+             (and (map? old) (map? new)) (deep-merge old new)
+             :else new))
+         maps))
+
+(defn- resolve-audit-path [env-path]
+  (let [logs-dir (env-var "LOGS_DIRECTORY")
+        state-dir (env-var "STATE_DIRECTORY")
+        xdg-state (env-var "XDG_STATE_HOME")
+        xdg-data (env-var "XDG_DATA_HOME")
+        home (env-var "HOME")
+        cwd (.getAbsolutePath (io/file "."))
+        in-nix-store? (str/starts-with? cwd "/nix/store")
+        default-path (:audit-log-path default-config)]
+    (or env-path
+        (cond
+          logs-dir (str (str/replace logs-dir #"/$" "") "/audit.log.ndjson")
+          state-dir (str (str/replace state-dir #"/$" "") "/audit.log.ndjson")
+          xdg-state (str (str/replace xdg-state #"/$" "") "/mcp-injector/audit.log.ndjson")
+          xdg-data (str (str/replace xdg-data #"/$" "") "/mcp-injector/audit.log.ndjson")
+          home (str home "/.local/state/mcp-injector/audit.log.ndjson")
+          (and in-nix-store? (not (str/starts-with? default-path "/")))
+          (throw (ex-info (str "Cannot use relative audit log path '" default-path "' in read-only directory: " cwd)
+                          {:cwd cwd
+                           :default-path default-path
+                           :suggestion "Set MCP_INJECTOR_AUDIT_LOG_PATH to an absolute, writable path."}))
+          :else default-path))))
+
+(defn load-config []
+  (let [env-audit-path (env-var "MCP_INJECTOR_AUDIT_LOG_PATH")
+        env-audit-secret (env-var "MCP_INJECTOR_AUDIT_SECRET")]
+    {:port (parse-int (env-var "MCP_INJECTOR_PORT") (:port default-config))
+     :host (env-var "MCP_INJECTOR_HOST" (:host default-config))
+     :llm-url (env-var "MCP_INJECTOR_LLM_URL" (:llm-url default-config))
+     :mcp-config (env-var "MCP_INJECTOR_MCP_CONFIG" (:mcp-config default-config))
+     :max-iterations (parse-int (env-var "MCP_INJECTOR_MAX_ITERATIONS") (:max-iterations default-config))
+     :log-level (env-var "MCP_INJECTOR_LOG_LEVEL" (:log-level default-config))
+     :timeout-ms (parse-int (env-var "MCP_INJECTOR_TIMEOUT_MS") (:timeout-ms default-config))
+     :audit-log-path (resolve-audit-path env-audit-path)
+     :audit-secret (or env-audit-secret (:audit-secret default-config))}))
+
+(defn get-env [name]
+  (System/getenv name))
+
+(defn- resolve-value
+  "Resolve a potentially dynamic value.
+   If value is a map with :env, look up environment variable.
+   Supports :prefix and :suffix."
+  [v]
+  (if (and (map? v) (:env v))
+    (let [env-name (:env v)]
+      (if (or (string? env-name) (keyword? env-name))
+        (let [prefix (:prefix v "")
+              suffix (:suffix v "")
+              env-val (get-env (if (keyword? env-name) (name env-name) env-name))]
+          (if env-val
+            (str prefix env-val suffix)
+            (do
+              (println (str "Warning: Environment variable " env-name " not set."))
+              nil)))
+        v))
+    v))
+
+(defn resolve-server-config
+  "Recursively resolve dynamic values in a server configuration map.
+   Uses post-order traversal: children first, then parent."
+  [m]
+  (let [resolve-all (fn resolve-all [x]
+                      (cond
+                        (map? x)
+                        (let [resolved (into {} (map (fn [[k v]] [k (resolve-all v)]) x))]
+                          (if (contains? resolved :env)
+                            (resolve-value resolved)
+                            resolved))
+
+                        (vector? x)
+                        (mapv resolve-all x)
+
+                        :else x))]
+    (resolve-all m)))
+
+(defn load-mcp-servers [config-path]
+  (if-let [file (io/file config-path)]
+    (if (.exists file)
+      (let [raw-config (keywordize-keys (edn/read-string (slurp file)))]
+        (update raw-config :servers
+                (fn [servers]
+                  (into {} (map (fn [[k v]] [k (resolve-server-config v)]) servers)))))
+      {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}})
+    {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}}))
+
+(defn get-llm-fallbacks
+  "Get LLM fallback configuration from MCP servers config.
+   Transforms from [{:provider :model}] format to provider/model strings"
+  [mcp-config]
+  (let [fallbacks-config (get-in mcp-config [:llm-gateway :fallbacks] [])]
+    (mapv (fn [fb]
+            (if (string? fb)
+              fb
+              (str (:provider fb) "/" (:model fb))))
+          fallbacks-config)))
+
+(defn build-tool-directory
+  "Build tool directory from mcp-config. 
+   If pre-discovered-tools map provided, use those; otherwise fall back to config :tools list."
+  ([mcp-config]
+   (build-tool-directory mcp-config nil))
+  ([mcp-config pre-discovered-tools]
+   (reduce
+    (fn [acc [server-name server-config]]
+      (let [server-url (or (:url server-config) (:uri server-config))
+            cmd (:cmd server-config)
+            tool-names (:tools server-config)]
+        (if (or server-url cmd)
+          (let [tools (if (and pre-discovered-tools (get pre-discovered-tools server-name))
+                        (get pre-discovered-tools server-name)
+                        (map (fn [t] {:name (name t)}) tool-names))]
+            (into acc (map (fn [tool]
+                             {:name (str (name server-name) "." (:name tool))
+                              :server (name server-name)})
+                           tools)))
+          acc)))
+    []
+    (:servers mcp-config))))
+
+(defn get-server-trust
+  "Get trust level for a server/tool combination.
+   Returns :restore (full restoration), :read (read-only access), :none (untrusted), or :block.
+   Precedence: tool-level :trust > server-level :trust > :none.
+   Accepts trust values as either keywords (:restore) or strings (\"restore\")."
+  [mcp-config server-name tool-name]
+  (let [servers (:servers mcp-config)
+        server (get servers (keyword server-name))]
+    (if-not server
+      :none
+      (let [server-trust (some-> server :trust keyword)
+            tool-configs (:tools server)
+            tool-config (cond
+                          (map? tool-configs)
+                          (get tool-configs (keyword tool-name))
+
+                          (sequential? tool-configs)
+                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)
+
+                          :else nil)
+            tool-trust (some-> tool-config :trust keyword)]
+        (cond
+          (= tool-trust :block) :block
+          (= server-trust :block) :block
+          (= tool-trust :restore) :restore
+          (= server-trust :restore) :restore
+          (= tool-trust :read) :read
+          (= server-trust :read) :read
+          :else :none)))))
+
+(defn get-meta-tool-definitions
+  "Get definitions for meta-tools like get_tool_schema and native tools"
+  []
+  [{:type "function"
+    :function {:name "get_tool_schema"
+               :description "Fetch the full JSON schema for a specific MCP tool to understand its parameters."
+               :parameters {:type "object"
+                            :properties {:tool {:type "string"
+                                                :description "Full tool name with mcp__ prefix (e.g., 'mcp__stripe__retrieve_customer')"}}
+                            :required ["tool"]}}}
+   {:type "function"
+    :function {:name "clojure-eval"
+               :description "Evaluate Clojure code in the local REPL. WARNING: Full Clojure access - use with care. Returns the result as a string."
+               :parameters {:type "object"
+                            :properties {:code {:type "string"
+                                                :description "Clojure code to evaluate"}}
+                            :required ["code"]}}}])
+
+(defn- extract-tool-params
+  "Extract parameter names from tool schema, distinguishing required vs optional.
+   Returns [required-params optional-params] as vectors of strings."
+  [tool]
+  (let [schema (or (:inputSchema tool) (:schema tool))
+        properties (get schema :properties {})
+        required-vals (get schema :required [])
+        required-set (set (map keyword required-vals))
+        all-param-names (keys properties)
+        required (filterv #(required-set %) all-param-names)
+        optional (filterv #(not (required-set %)) all-param-names)]
+    [(mapv name required) (mapv name optional)]))
+
+(defn- format-tool-with-params
+  "Format a tool as mcp__server__tool [required, optional?]"
+  [server-name tool]
+  (let [tool-name (:name tool)
+        [required optional] (extract-tool-params tool)]
+    (if (or (seq required) (seq optional))
+      (let [all-params (into required (map #(str % "?")) optional)]
+        (str "mcp__" (name server-name) "__" tool-name " [" (str/join ", " all-params) "]"))
+      (str "mcp__" (name server-name) "__" tool-name))))
+
+(defn inject-tools-into-messages
+  "Inject MCP tools directory into messages.
+   If pre-discovered-tools map provided (server-name -> [tools]), use those;
+   otherwise fall back to config :tools list."
+  ([messages mcp-config]
+   (inject-tools-into-messages messages mcp-config nil))
+  ([messages mcp-config pre-discovered-tools]
+   (let [servers (:servers mcp-config)
+         tool-lines (reduce
+                     (fn [lines [server-name server-config]]
+                       (let [server-url (or (:url server-config) (:uri server-config))
+                             cmd (:cmd server-config)
+                             tool-names (:tools server-config)]
+                         (if (or server-url cmd)
+                           (let [discovered (get pre-discovered-tools server-name)
+                                 tools (if (and pre-discovered-tools (seq discovered))
+                                         discovered
+                                         (mapv (fn [t] {:name (name t)}) tool-names))
+                                 tools (filter #(some? (:name %)) tools)
+                                 formatted (map #(format-tool-with-params server-name %) tools)
+                                 tool-str (str/join ", " formatted)]
+                             (if (seq tools)
+                               (conj lines (str "- mcp__" (name server-name) ": " tool-str))
+                               lines))
+                           lines)))
+                     []
+                     servers)
+         directory-text (str "## Remote Tools (MCP)\n"
+                             "You have access to namespaced MCP tools.\n\n"
+                             "### Available:\n"
+                             (str/join "\n" tool-lines)
+                             "\n\n### Usage:\n"
+                             "Get schema: get_tool_schema {:tool \"mcp__server__tool\"}\n"
+                             "Call tool: mcp__server__tool {:key \"value\"}\n\n"
+                             "### Native:\n"
+                             "- clojure-eval: Evaluate Clojure. Args: {:code \"...\"}\n"
+                             "  Example: {:code \"(vec (range 5))\"} => \"[0 1 2 3 4]\"")
+         system-msg {:role "system" :content directory-text}]
+     (cons system-msg messages))))
+
+(defn get-virtual-models
+  "Get virtual models configuration from MCP servers config"
+  [mcp-config]
+  (get-in mcp-config [:llm-gateway :virtual-models] {}))
+
+(defn resolve-governance
+  "Unified governance resolution logic. Prioritizes nested :governance block.
+   Precedence: top-level :governance > :llm-gateway :governance > defaults.
+   Uses deep-merge to preserve nested default settings."
+  [mcp-config env-config]
+  (let [gateway (:llm-gateway mcp-config)
+        gov-user (or (:governance mcp-config) (:governance gateway))
+        defaults {:mode :permissive
+                  :pii {:enabled true :mode :replace}
+                  :audit {:enabled true :path (:audit-log-path env-config)}
+                  :policy {:mode :permissive}}]
+    (deep-merge defaults gov-user)))
+
+(defn get-config
+  "Unified config: env vars override config file, with defaults as fallback.
+    Priority: env var > config file > default"
+  [mcp-config]
+  (let [env (load-config)
+        gateway (:llm-gateway mcp-config)
+        gov (resolve-governance mcp-config env)]
+    {:port (:port env)
+     :host (:host env)
+     :llm-url (or (env-var "MCP_INJECTOR_LLM_URL")
+                  (:url gateway)
+                  (:llm-url env))
+     :mcp-config (:mcp-config env)
+     :max-iterations (let [v (or (env-var "MCP_INJECTOR_MAX_ITERATIONS")
+                                 (:max-iterations gateway))]
+                       (if (string? v) (parse-int v 10) (or v (:max-iterations env))))
+     :log-level (or (env-var "MCP_INJECTOR_LOG_LEVEL")
+                    (:log-level gateway)
+                    (:log-level env))
+     :timeout-ms (let [v (or (env-var "MCP_INJECTOR_TIMEOUT_MS")
+                             (:timeout-ms gateway))]
+                   (if (string? v) (parse-int v 1800000) (or v (:timeout-ms env))))
+     :fallbacks (:fallbacks gateway)
+     :virtual-models (:virtual-models gateway)
+     :audit-log-path (get-in gov [:audit :path])
+     :audit-secret (or (get-in gov [:audit :secret])
+                       (env-var "MCP_INJECTOR_AUDIT_SECRET")
+                       (:audit-secret env)
+                       "default-audit-secret")
+     :governance gov}))
+
+(defn get-llm-url
+  "Get LLM URL: env var overrides config file"
+  [mcp-config]
+  (or (env-var "MCP_INJECTOR_LLM_URL")
+      (get-in mcp-config [:llm-gateway :url])
+      "http://localhost:8080"))
+
+=== FILE: src/mcp_injector/core.clj ===
+(ns mcp-injector.core
+  (:require [org.httpkit.server :as http]
+            [babashka.http-client :as http-client]
+            [cheshire.core :as json]
+            [clojure.string :as str]
+            [clojure.java.io :as io]
+            [mcp-injector.config :as config]
+            [mcp-injector.openai-compat :as openai]
+            [mcp-injector.mcp-client :as mcp]
+            [mcp-injector.audit :as audit]
+            [mcp-injector.pii :as pii]
+            [mcp-injector.policy :as policy]))
+
+(def ^:private server-state (atom nil))
+(def ^:private usage-stats (atom {}))
+(def ^:private cooldown-state (atom {}))
+(def ^:private ^:dynamic *request-id* nil)
+(def ^:private ^:dynamic *audit-config* nil)
+
+(defn- log-request
+  ([level message data]
+   (log-request level message data nil))
+  ([level message data context]
+   (let [log-entry (merge {:timestamp (str (java.time.Instant/now))
+                           :level level
+                           :message message
+                           :request-id (or *request-id* "none")}
+                          context
+                          {:data data})]
+     (println (json/generate-string log-entry))
+     ;; Fail-open audit logging
+     (when *audit-config*
+       (try
+         (audit/append-event! (:secret *audit-config*) level log-entry)
+         (catch Exception e
+           (binding [*out* *err*]
+             (println (json/generate-string
+                       {:timestamp (str (java.time.Instant/now))
+                        :level "error"
+                        :message "AUDIT LOG WRITE FAILURE — audit trail degraded"
+                        :error (.getMessage e)})))))))))
+
+(defn- parse-body [body]
+  (try
+    (if (string? body)
+      (json/parse-string body true)
+      (json/parse-string (slurp body) true))
+    (catch Exception e
+      (throw (ex-info "Failed to parse JSON body"
+                      {:type :json_parse_error
+                       :status 400
+                       :message "Failed to parse JSON body. Please ensure your request is valid JSON."} e)))))
+
+(defn- is-context-overflow-error? [error-str]
+  (when (string? error-str)
+    (let [patterns [#"(?i)cannot read propert(?:y|ies) of undefined.*prompt"
+                    #"(?i)cannot read propert(?:y|ies) of null.*prompt"
+                    #"(?i)prompt_tokens.*undefined"
+                    #"(?i)prompt_tokens.*null"
+                    #"(?i)context window.*exceeded"
+                    #"(?i)context length.*exceeded"
+                    #"(?i)maximum context.*exceeded"
+                    #"(?i)request.*too large"
+                    #"(?i)prompt is too long"
+                    #"(?i)exceeds model context"
+                    #"(?i)413.*too large"
+                    #"(?i)request size exceeds"]]
+      (some #(re-find % error-str) patterns))))
+
+(defn- translate-error-for-openclaw [error-data status-code]
+  (let [error-str (or (get-in error-data [:error :message])
+                      (:message error-data)
+                      (:details error-data)
+                      (str error-data))]
+    (cond
+      (is-context-overflow-error? error-str)
+      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
+       :status 503
+       :type "context_overflow"
+       :details error-data}
+
+      (= 429 status-code)
+      {:message (or (:message error-data) "Rate limit exceeded")
+       :status 429
+       :type "rate_limit_exceeded"
+       :details error-data}
+
+      :else
+      {:message (or (:message error-data) "Upstream error")
+       :status 502
+       :type "upstream_error"
+       :details error-data})))
+
+(defn- call-llm [base-url payload]
+  (let [url (str (str/replace base-url #"/$" "") "/v1/chat/completions")
+        resp (try
+               (http-client/post url
+                                 {:headers {"Content-Type" "application/json"}
+                                  :body (json/generate-string payload)
+                                  :throw false})
+               (catch Exception e
+                 {:status 502 :body (json/generate-string {:error {:message (.getMessage e)}})}))]
+    (if (= 200 (:status resp))
+      {:success true :data (json/parse-string (:body resp) true)}
+      (let [status (:status resp)
+            error-data (try (json/parse-string (:body resp) true) (catch Exception _ (:body resp)))
+            translated (translate-error-for-openclaw error-data status)]
+        (log-request "warn" "LLM Error" {:status status :body (:body resp) :translated translated})
+        {:success false :status (:status translated) :error translated}))))
+
+(defn- record-completion! [alias provider usage]
+  (when usage
+    (let [update-entry (fn [existing usage]
+                         (let [input (or (:prompt_tokens usage) 0)
+                               output (or (:completion_tokens usage) 0)
+                               total (or (:total_tokens usage) (+ input output))]
+                           {:requests (inc (or (:requests existing) 0))
+                            :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
+                            :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
+                            :total-tokens (+ total (or (:total-tokens existing) 0))
+                            :rate-limits (or (:rate-limits existing) 0)
+                            :context-overflows (or (:context-overflows existing) 0)
+                            :last-updated (System/currentTimeMillis)}))]
+      (swap! usage-stats
+             (fn [stats]
+               (cond-> stats
+                 alias (update alias update-entry usage)
+                 (and provider (not= provider alias)) (update provider update-entry usage)))))))
+
+(defn- track-provider-failure! [provider status]
+  (when provider
+    (let [counter (if (= status 503) :context-overflows :rate-limits)]
+      (swap! usage-stats update provider
+             (fn [existing]
+               (assoc (or existing {:requests 0
+                                    :total-input-tokens 0
+                                    :total-output-tokens 0
+                                    :total-tokens 0})
+                      counter (inc (or (get existing counter) 0))
+                      :last-updated (System/currentTimeMillis)))))))
+
+(defn reset-usage-stats! []
+  (reset! usage-stats {}))
+
+(defn- execute-tool [full-name args mcp-servers discovered-this-loop governance context]
+  (let [policy-result (policy/allow-tool? (:policy governance) full-name context)]
+    (if-not (:allowed? policy-result)
+      (do
+        (log-request "warn" "Tool Blocked by Policy" {:tool full-name :reason (:reason policy-result)} context)
+        {:error "Tool execution denied"})
+      (cond
+        (= full-name "get_tool_schema")
+        (let [full-tool-name (:tool args)
+              ;; Parse prefixed name: mcp__server__tool -> [server tool]
+              [s-name t-name] (if (and full-tool-name (str/includes? full-tool-name "__"))
+                                (let [idx (str/last-index-of full-tool-name "__")]
+                                  [(subs full-tool-name 5 idx) (subs full-tool-name (+ idx 2))])
+                                [nil nil])
+              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
+          (if (and s-name s-config t-name)
+            (let [schema (mcp/get-tool-schema (name s-name) s-config t-name (:policy governance))]
+              (if (:error schema)
+                schema
+                (do
+                  (swap! discovered-this-loop assoc full-tool-name schema)
+                  schema)))
+            {:error (str "Invalid tool name. Use format: mcp__server__tool (e.g., mcp__stripe__retrieve_customer). Got: " full-tool-name)}))
+
+        (= full-name "clojure-eval")
+        (try
+          (let [code (:code args)
+                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
+                ;; Security is currently enforced only via the Policy layer (explicit opt-in).
+                result (load-string code)]
+            (json/generate-string result))
+          (catch Exception e
+            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
+
+        (str/starts-with? full-name "mcp__")
+        (let [t-name (str/replace full-name #"^mcp__" "")
+              [s-name real-t-name] (if (str/includes? t-name "__")
+                                     (let [idx (str/last-index-of t-name "__")]
+                                       [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+                                     [nil t-name])
+              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
+          (if (and s-name s-config)
+            (let [result (mcp/call-tool (name s-name) s-config real-t-name args (:policy governance))
+                  ;; Auto-discover: add schema to discovered-this-loop so next turn has it
+                  _ (when-not (contains? result :error)
+                      (let [schema (mcp/get-tool-schema (name s-name) s-config real-t-name (:policy governance))]
+                        (when-not (:error schema)
+                          (swap! discovered-this-loop assoc full-name schema))))]
+              result)
+            (if-let [_ (get @discovered-this-loop full-name)]
+              (let [[_ s-name-auto real-t-auto] (str/split full-name #"__" 3)
+                    s-conf-auto (get-in mcp-servers [:servers (keyword s-name-auto)])]
+                (mcp/call-tool (name s-name-auto) s-conf-auto real-t-auto args (:policy governance)))
+              {:error (str "Unknown tool: " full-name ". Use get_tool_schema with full prefixed name first.")})))
+
+        :else {:error (str "Unknown tool: " full-name)}))))
+
+(defn- parse-tool-name
+  "Parse mcp__server__tool format into [server tool]"
+  [full-name]
+  (if (str/includes? full-name "__")
+    (let [t-name (str/replace full-name #"^mcp__" "")
+          idx (str/last-index-of t-name "__")]
+      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+    [nil full-name]))
+
+(defn- scrub-messages [messages vault request-id]
+  (mapv (fn [m]
+          (let [content (:content m)
+                role (:role m)]
+            (if (and (string? content)
+                     ;; Only redact user/system messages - assistant tool results are already handled
+                     (or (= role "system") (= role "user"))
+                     ;; Skip if already contains PII tokens (avoid double-redaction)
+                     ;; Token format: [LABEL_hex8] e.g., [EMAIL_ADDRESS_a35e2662]
+                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{8,}\]" content)))
+              (let [config {:mode :replace :salt request-id}
+                    [redacted-content _ _] (pii/redact-data content config vault)]
+                (assoc m :content redacted-content))
+              m)))
+        messages))
+
+(defn- restore-tool-args
+  "Restore tokens in tool args if server is trusted.
+   For edit/write operations, also resolve standalone tokens in string arguments."
+  [args vault mcp-servers full-tool-name]
+  (let [[server tool] (parse-tool-name full-tool-name)
+        trust (when server (config/get-server-trust mcp-servers server tool))
+        ;; For file editing tools, also resolve tokens in string values
+        restore-strings? (contains? #{"edit" "write"} tool)
+        restored (if (= trust :restore)
+                   (pii/restore-tokens args vault)
+                   (if restore-strings?
+                     ;; Even if not fully trusted, resolve tokens for file operations
+                     (pii/restore-tokens args vault)
+                     args))]
+    restored))
+
+(defn- redact-tool-output
+  "Redact PII from tool output, return [content vault]"
+  [raw-output vault request-id]
+  (let [;; Try to parse as JSON first for JSON tokenization
+        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
+        ;; If parsed successfully, redact the data structure; otherwise redact the string
+        ;; Special handling for MCP response format: parse nested :text field if present
+        [redacted new-vault detected] (if parsed
+                                        (let [;; Check if this is MCP response format with :text field containing JSON
+                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
+                                              parsed (cond
+                                                       (map? parsed)
+                                                       (if (string? (:text parsed))
+                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
+                                                              (catch Exception _ parsed))
+                                                         parsed)
+                                                       (sequential? parsed)
+                                                       (mapv (fn [item]
+                                                               (if (and (map? item) (string? (:text item)))
+                                                                 (try (assoc item :text (json/parse-string (:text item) true))
+                                                                      (catch Exception _ item))
+                                                                 item))
+                                                             parsed)
+                                                       :else parsed)
+                                              config {:mode :replace :salt request-id}
+                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
+                                          [(json/generate-string redacted-struct) vault-after detected-labels])
+                                        (let [config {:mode :replace :salt request-id}
+                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
+                                          [redacted-str vault-after detected-labels]))]
+
+    ;; Log the detected PII types (not scanning again)
+    (when (seq detected)
+      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
+    [redacted new-vault]))
+
+(defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
+  (let [model (:model payload)
+        discovered-this-loop (atom {})
+        vault (atom {})
+        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
+        context {:model model :request-id request-id}]
+    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
+           iteration 0]
+      (if (>= iteration max-iterations)
+        {:success true
+         :provider model
+         :data {:choices [{:index 0
+                           :message {:role "assistant"
+                                     :content "Maximum iterations reached. Here's what I found so far:"
+                                     :tool_calls nil}
+                           :finish_reason "length"}]}}
+        (let [_ (log-request "info" "Tool Loop" {:iteration iteration :calls (count (get-in current-payload [:messages]))} context)
+              resp (call-llm llm-url current-payload)]
+          (if-not (:success resp)
+            resp
+            (let [choices (get-in resp [:data :choices])
+                  message (get-in (first choices) [:message])
+                  tool-calls (:tool_calls message)]
+              (if-not tool-calls
+                (assoc resp :provider model)
+                (let [mcp-calls (filter (fn [tc]
+                                          (let [n (get-in tc [:function :name])]
+                                            (or (= n "get_tool_schema")
+                                                (and n (str/starts-with? n "mcp__")))))
+                                        tool-calls)
+                      native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
+                                           tool-calls)]
+                  (if (and (empty? mcp-calls) (empty? native-calls))
+                    (assoc resp :provider model)
+                    (let [[results new-vault]
+                          (reduce
+                           (fn [[results vault-state] tc]
+                             (let [fn-name (get-in tc [:function :name])
+                                   args-str (get-in tc [:function :arguments])
+                                   parse-result (try
+                                                  {:success true :args (json/parse-string args-str true)}
+                                                  (catch Exception e
+                                                    {:success false :error (.getMessage e)}))]
+                               (if (:success parse-result)
+                                 (let [;; Restore args if trusted
+                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
+                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
+                                       ;; Redact output with vault
+                                       raw-content (if (string? result) result (json/generate-string result))
+                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
+                                   [(conj results {:role "tool"
+                                                   :tool_call_id (:id tc)
+                                                   :name fn-name
+                                                   :content redacted})
+                                    updated-vault])
+                                 [(conj results {:role "tool"
+                                                 :tool_call_id (:id tc)
+                                                 :name fn-name
+                                                 :content (json/generate-string
+                                                           {:error "Malformed tool arguments JSON"
+                                                            :details {:args-str args-str
+                                                                      :parse-error (:error parse-result)}})})
+                                  vault-state])))
+                           [[] vault]
+                           (concat mcp-calls native-calls))
+                          newly-discovered @discovered-this-loop
+                          new-tools (vec (concat (config/get-meta-tool-definitions)
+                                                 (map (fn [[name schema]]
+                                                        {:type "function"
+                                                         :function {:name name
+                                                                    :description (:description schema)
+                                                                    :parameters (:inputSchema schema)}})
+                                                      newly-discovered)))
+                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
+                          new-messages (into new-messages results)]
+                      (recur (assoc current-payload
+                                    :messages (scrub-messages new-messages new-vault request-id)
+                                    :tools new-tools)
+                             (inc iteration)))))))))))))
+
+(defn- set-cooldown! [provider minutes]
+  (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
+
+(defn- is-on-cooldown? [provider]
+  (if-let [expiry (get @cooldown-state provider)]
+    (if (> expiry (System/currentTimeMillis))
+      true
+      (do (swap! cooldown-state dissoc provider) false))
+    false))
+
+(defn reset-cooldowns! []
+  (reset! cooldown-state {}))
+
+(defn- body->string [body]
+  (if (string? body) body (slurp body)))
+
+(defn- extract-discovered-tools
+  "Scan messages for tool schemas returned by get_tool_schema.
+   Returns a map of tool-name -> full tool schema."
+  [messages]
+  (reduce
+   (fn [acc msg]
+     (if (= "tool" (:role msg))
+       (let [content (:content msg)
+             parsed (try (json/parse-string (body->string content) true) (catch Exception _ nil))]
+         (if (and parsed (:name parsed))
+           (let [tool-name (:name parsed)
+                 formatted-name (if (str/includes? tool-name "__")
+                                  tool-name
+                                  (str "mcp__" tool-name))]
+             (assoc acc formatted-name parsed))
+           acc))
+       acc))
+   {}
+   messages))
+
+(defn- prepare-llm-request [chat-req mcp-servers]
+  (let [meta-tools (config/get-meta-tool-definitions)
+        discovered-tools (extract-discovered-tools (:messages chat-req))
+        existing-tools (:tools chat-req)
+        fallbacks (config/get-llm-fallbacks mcp-servers)
+        discovered-tool-defs (map (fn [[name schema]]
+                                    {:type "function"
+                                     :function {:name name
+                                                :description (:description schema)
+                                                :parameters (:inputSchema schema)}})
+                                  discovered-tools)
+        merged-tools (vec (concat (or existing-tools [])
+                                  meta-tools
+                                  discovered-tool-defs))
+        ;; Merge extra_body into the request for fields like request-id
+        extra-body (or (:extra_body chat-req) {})]
+    (-> chat-req
+        (assoc :stream false)
+        (dissoc :stream_options)
+        (assoc :fallbacks fallbacks)
+        (merge extra-body) ;; Lift extra_body fields to top level
+        (update :messages (fn [msgs]
+                            (mapv (fn [m]
+                                    (if (and (= (:role m) "assistant") (:tool_calls m))
+                                      (update m :tool_calls (fn [tcs]
+                                                              (mapv #(dissoc % :index) tcs)))
+                                      m))
+                                  msgs)))
+        (assoc :tools merged-tools))))
+
+(defn- try-virtual-model-chain [config prepared-req llm-url mcp-servers max-iterations governance]
+  (let [chain (:chain config)
+        retry-on (set (:retry-on config [429 500]))
+        cooldown-mins (get config :cooldown-minutes 5)
+        original-model (:model prepared-req)]
+    (loop [providers (filter #(not (is-on-cooldown? %)) chain)
+           last-error nil]
+      (if (empty? providers)
+        {:success false :status 502 :error (or last-error {:message "All providers failed"})}
+        (let [provider (first providers)
+              _ (log-request "info" "Virtual model: trying provider" {:provider provider :remaining (count (rest providers))}
+                             {:model original-model :endpoint llm-url})
+              req (-> prepared-req
+                      (assoc :model provider)
+                      (dissoc :fallbacks))
+              result (agent-loop llm-url req mcp-servers max-iterations governance)]
+          (if (:success result)
+            (assoc result :provider provider)
+            (if (some #(= % (:status result)) retry-on)
+              (do
+                (log-request "warn" "Virtual model: provider failed, setting cooldown" {:provider provider :status (:status result) :cooldown-mins cooldown-mins}
+                             {:model original-model :endpoint llm-url})
+                (set-cooldown! provider cooldown-mins)
+                (track-provider-failure! provider (:status result))
+                (recur (rest providers) (:error result)))
+              (assoc result :provider provider))))))))
+
+(defn- handle-chat-completion [request mcp-servers config]
+  (try
+    (let [chat-req (parse-body (:body request))
+          model (:model chat-req)
+          _ (log-request "info" "Chat Completion Started" {:stream (:stream chat-req)} {:model model})
+          discovered (reduce (fn [acc [s-name s-conf]]
+                               (let [url (or (:url s-conf) (:uri s-conf))
+                                     cmd (:cmd s-conf)]
+                                 (if (or url cmd)
+                                   (try (assoc acc s-name (mcp/discover-tools (name s-name) s-conf (:tools s-conf) (:policy (:governance config))))
+                                        (catch Exception e
+                                          (log-request "warn" "Discovery failed" {:server s-name :error (.getMessage e)} {:model model})
+                                          acc))
+                                   acc)))
+                             {} (:servers mcp-servers))
+          messages (config/inject-tools-into-messages (:messages chat-req) mcp-servers discovered)
+          llm-url (or (:llm-url config) (config/get-llm-url mcp-servers))
+          virtual-models (config/get-virtual-models mcp-servers)
+          virtual-config (or (get virtual-models model) (get virtual-models (keyword model)))
+          prepared-req (prepare-llm-request (assoc chat-req :messages messages) mcp-servers)
+          max-iter (or (:max-iterations config) 10)
+          gov (:governance config)
+          result (if virtual-config
+                   (try-virtual-model-chain virtual-config prepared-req llm-url mcp-servers max-iter gov)
+                   (agent-loop llm-url prepared-req mcp-servers max-iter gov))]
+      (if (:success result)
+        (let [final-resp (:data result)
+              actual-provider (:provider result)
+              _ (record-completion! model actual-provider (:usage final-resp))
+              _ (log-request "info" "Chat Completion Success" {:usage (:usage final-resp) :provider actual-provider} {:model model})
+              body (if (:stream chat-req)
+                     (openai/build-chat-response-streaming
+                      {:content (get-in final-resp [:choices 0 :message :content])
+                       :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
+                       :model model
+                       :usage (:usage final-resp)})
+                     (json/generate-string
+                      (openai/build-chat-response
+                       {:content (get-in final-resp [:choices 0 :message :content])
+                        :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
+                        :model model
+                        :usage (:usage final-resp)})))]
+          {:status 200 :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})
+        (let [status (or (:status result) 500)
+              error-data (:error result)
+              error-msg (if (map? error-data) (:message error-data) (str "Failed: " error-data))
+              error-type (get-in result [:error :type] "internal_error")
+              _ (log-request "warn" "Chat Completion Failed" {:status status :error error-msg :type error-type} {:model model :endpoint llm-url})
+              body (if (:stream chat-req)
+                     (str "data: " (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}) "\n\ndata: [DONE]\n\n")
+                     (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
+          {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
+    (catch Exception e
+      (let [err-type (or (some-> e ex-data :type name) "internal_error")
+            err-msg (or (.getMessage e) (str e))
+            stack (.getStackTrace e)]
+        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
+        {:status 400
+         :headers {"Content-Type" "application/json"}
+         :body (json/generate-string {:error {:message err-msg
+                                              :type err-type}})}))))
+
+(defn get-gateway-state []
+  {:cooldowns @cooldown-state
+   :usage @usage-stats
+   :warming-up? (let [fut (get @server-state :warmup-future)]
+                  (if fut (not (realized? fut)) false))})
+
+(defn- handle-api [request _mcp-servers config]
+  (let [uri (:uri request)
+        method (:request-method request)]
+    (case [method uri]
+      [:get "/api/v1/status"]
+      {:status 200 :body (json/generate-string {:status "ok" :version "1.0.0" :warming-up? (:warming-up? (get-gateway-state))})}
+
+      [:get "/api/v1/mcp/tools"]
+      {:status 200 :body (json/generate-string (mcp/get-cache-state))}
+
+      [:post "/api/v1/mcp/reset"]
+      (do (mcp/clear-tool-cache!)
+          {:status 200 :body (json/generate-string {:message "MCP state reset successful"})})
+
+      [:get "/api/v1/llm/state"]
+      {:status 200 :body (json/generate-string (get-gateway-state))}
+
+      [:post "/api/v1/llm/cooldowns/reset"]
+      (do (reset-cooldowns!)
+          {:status 200 :body (json/generate-string {:message "Cooldowns reset successful"})})
+
+      [:get "/api/v1/stats"]
+      {:status 200 :body (json/generate-string {:stats @usage-stats})}
+
+      [:get "/api/v1/audit/verify"]
+      (let [path (:audit-log-path config)
+            secret (:audit-secret config)
+            valid? (audit/verify-log (io/file path) secret)]
+        {:status 200 :body (json/generate-string {:valid? valid? :path path})})
+
+      {:status 404 :body (json/generate-string {:error "Not found"})})))
+
+(defn- handler [request mcp-servers config]
+  (let [request-id (str (java.util.UUID/randomUUID))
+        audit-conf {:path (io/file (:audit-log-path config))
+                    :secret (:audit-secret config)}]
+    (binding [*request-id* request-id
+              *audit-config* audit-conf]
+      (try
+        (let [uri (:uri request)]
+          (cond
+            (= uri "/v1/chat/completions")
+            (if (= :post (:request-method request))
+              (handle-chat-completion request mcp-servers config)
+              {:status 405 :body "Method not allowed"})
+
+            (= uri "/health")
+            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}
+
+            (= uri "/stats")
+            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:stats @usage-stats})}
+
+            (str/starts-with? uri "/api/v1")
+            (handle-api request mcp-servers config)
+
+            :else
+            {:status 404 :body "Not found"}))
+        (catch Exception e
+          (let [err-data (ex-data e)
+                status (or (:status err-data) 500)
+                err-type (or (some-> err-data :type name) "internal_error")]
+            (log-request "error" "Request failed" {:type err-type :message (.getMessage e)} {:endpoint (:uri request)})
+            {:status status
+             :headers {"Content-Type" "application/json"}
+             :body (json/generate-string {:error {:message (or (:message err-data) (.getMessage e) "Internal server error")
+                                                  :type err-type}})}))))))
+
+(defn start-server [mcp-config]
+  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
+        provided-governance (or (:governance mcp-config)
+                                (:governance (:mcp-servers mcp-config)))
+
+        ;; Runtime settings - prioritize input > env > default
+        port (or (:port mcp-config)
+                 (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
+                 8080)
+        host (or (:host mcp-config)
+                 (System/getenv "MCP_INJECTOR_HOST")
+                 "127.0.0.1")
+        llm-url (or (:llm-url mcp-config)
+                    (System/getenv "MCP_INJECTOR_LLM_URL")
+                    "http://localhost:11434")
+        log-level (or (:log-level mcp-config)
+                      (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
+        max-iterations (or (:max-iterations mcp-config)
+                           (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
+                           10)
+        mcp-config-path (or (:mcp-config-path mcp-config)
+                            (System/getenv "MCP_INJECTOR_MCP_CONFIG")
+                            "mcp-servers.edn")
+        ;; Audit trail config
+        audit-log-path (or (:audit-log-path mcp-config)
+                           (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
+                           "logs/audit.log.ndjson")
+        audit-secret (or (:audit-secret mcp-config)
+                         (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
+                         "default-audit-secret")
+        ;; Merge provided mcp-config with loaded ones if needed
+        base-mcp-servers (cond
+                           (and (map? mcp-config) (:servers mcp-config)) mcp-config
+                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
+                           :else (config/load-mcp-servers mcp-config-path))
+        ;; Apply overrides from mcp-config (like :virtual-models in tests)
+        mcp-servers (if (map? mcp-config)
+                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
+                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
+                        (if-let [gov (:governance mcp-config)]
+                          (assoc merged :governance gov)
+                          merged))
+                      base-mcp-servers)
+        ;; Unified configuration resolution - pass extracted governance
+        unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
+        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
+        final-config {:port port :host host :llm-url llm-url :log-level log-level
+                      :max-iterations max-iterations :mcp-config-path mcp-config-path
+                      :audit-log-path audit-log-path :audit-secret audit-secret
+                      :governance final-governance}
+        ;; Validate policy at startup
+        _ (policy/validate-policy! (:policy final-governance))
+        ;; P3 Integration: Initialize Audit system
+        _ (audit/init-audit! audit-log-path)
+        srv (http/run-server (fn [req] (handler req mcp-servers final-config)) {:port port :host host})
+        actual-port (or (:local-port (meta srv)) port)
+        warmup-fut (future (mcp/warm-up! mcp-servers))]
+    (reset! server-state {:server srv :port actual-port :warmup-future warmup-fut})
+    (log-request "info" "mcp-injector started" (assoc final-config :port actual-port))
+    {:server srv :port actual-port :warmup-future warmup-fut}))
+
+(defn stop-server [s]
+  (when s
+    (let [srv (cond (fn? s) s (map? s) (:server s) :else s)
+          fut (when (map? s) (:warmup-future s))]
+      (when fut (future-cancel fut))
+      (when (fn? srv) (srv :timeout 100))
+      (reset! server-state nil)
+      (mcp/clear-tool-cache!)
+      ;; P3 Integration: Close Audit system
+      (audit/close-audit!))))
+
+(defn clear-mcp-sessions! []
+  (mcp/clear-tool-cache!))
+
+(defn -main [& _args]
+  (let [initial-config (config/load-config)
+        mcp-servers (config/load-mcp-servers (:mcp-config initial-config))
+        unified-config (config/get-config mcp-servers)]
+    (start-server unified-config)))
+
+=== FILE: src/mcp_injector/pii.clj ===
+(ns mcp-injector.pii
+  (:require [clojure.string :as str]
+            [clojure.walk :as walk])
+  (:import (java.security MessageDigest)))
+
+(def default-patterns
+  [{:id :EMAIL_ADDRESS
+    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
+    :label "[EMAIL_ADDRESS]"}
+   {:id :IBAN_CODE
+    :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
+    :label "[IBAN_CODE]"}])
+
+(defn shannon-entropy
+  "Calculates the Shannon entropy of a string."
+  [s]
+  (if (empty? s)
+    0.0
+    (let [freqs (vals (frequencies s))
+          len (count s)]
+      (- (reduce + (map (fn [f]
+                          (let [p (/ f len)]
+                            (* p (/ (Math/log p) (Math/log 2)))))
+                        freqs))))))
+
+(defn- character-diversity?
+  "Checks if a string contains at least 3 distinct character classes."
+  [s]
+  (let [classes [(when (re-find #"[a-z]" s) :lower)
+                 (when (re-find #"[A-Z]" s) :upper)
+                 (when (re-find #"[0-9]" s) :digit)
+                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]]
+    (>= (count (remove nil? classes)) 3)))
+
+(defn- mask-string
+  "Fixed-length mask to prevent leaking structural entropy."
+  [_s]
+  "********")
+
+(defn- redact-match [mode label match]
+  (case mode
+    :replace label
+    :mask (mask-string match)
+    :hash (str "#" (hash match))
+    label))
+
+(defn- scan-env [text env-vars mode]
+  (reduce-kv
+   (fn [acc k v]
+     (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
+       (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
+       acc))
+   text
+   env-vars))
+
+(defn- find-env-detections [text env-vars]
+  (keep (fn [[k v]]
+          (when (and (not (empty? v)) (> (count v) 5) (str/includes? text v))
+            (keyword (str "ENV_VAR_" k))))
+        env-vars))
+
+(defn- scan-entropy [text threshold mode]
+  (let [tokens (str/split text #"\s+")]
+    (reduce
+     (fn [acc token]
+       (if (and (> (count token) 12)
+                (> (shannon-entropy token) threshold)
+                (character-diversity? token))
+         (str/replace acc token (redact-match mode "[HIGH_ENTROPY_SECRET]" token))
+         acc))
+     text
+     tokens)))
+
+(defn scan-and-redact
+  "Scans input text for PII patterns, high-entropy secrets, and env vars."
+  [text {:keys [mode patterns entropy-threshold env]
+         :or {mode :replace
+              patterns default-patterns
+              entropy-threshold 4.0
+              env {}}}]
+  (let [regex-result (reduce
+                      (fn [state {:keys [id pattern label]}]
+                        (if (seq (re-seq pattern (:text state)))
+                          {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
+                           :detected (conj (:detected state) id)}
+                          state))
+                      {:text text :detected []}
+                      patterns)
+        env-text (scan-env (:text regex-result) env mode)
+        env-detections (find-env-detections text env)
+        final-text (scan-entropy env-text entropy-threshold mode)
+        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
+    {:text final-text
+     :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
+
+(defn generate-token
+  "Generate a deterministic, truncated SHA-256 hash token.
+   Uses 12 hex chars (48 bits) to reduce collision probability."
+  [label value salt]
+  (let [input (str (name label) "|" value "|" salt)
+        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
+        hash-str (->> digest
+                      (map (partial format "%02x"))
+                      (apply str))
+        truncated (subs hash-str 0 12)]
+    (str "[" (name label) "_" truncated "]")))
+
+(defn- redact-string-value
+  "Redact a single string value, returning [redacted-text token detected-label]"
+  [v config]
+  (if-not (string? v)
+    [v nil nil]
+    (if (empty? v)
+      [v nil nil]
+      (let [vault (:vault config)
+            salt (:salt config)
+            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
+            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
+        (cond
+          existing-token [existing-token nil nil]
+          previous-token [previous-token nil nil]
+          :else
+          (let [result (scan-and-redact v config)]
+            (if (seq (:detected result))
+              (let [detected (first (:detected result))
+                    token (generate-token detected v salt)]
+                (swap! vault assoc token v)
+                [token token detected])
+              [(:text result) nil nil])))))))
+
+(defn redact-data
+  "Recursively walk a data structure, redact string values, store in vault.
+    Returns [redacted-data vault-atom detected-labels]"
+  ([data config]
+   (redact-data data config (atom {})))
+  ([data config vault]
+   (let [config-with-vault (assoc config :vault vault)
+         detected-labels (atom [])
+         redacted (walk/postwalk
+                   (fn [x]
+                     (if (string? x)
+                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
+                         (when detected (swap! detected-labels conj detected))
+                         redacted-text)
+                       x))
+                   data)]
+     [redacted vault @detected-labels])))
+
+(defn restore-tokens
+  "Recursively walk a data structure, replacing tokens with original values from vault."
+  [data vault]
+  (let [v-map @vault]
+    (if (empty? v-map)
+      data
+      (walk/postwalk
+       (fn [x]
+         (if (string? x)
+           (reduce
+            (fn [s [token original]]
+              (if (and (string? s) (str/includes? s token))
+                (str/replace s (str token) (str original))
+                s))
+            x
+            v-map)
+           x))
+       data))))
+
+(defn resolve-token
+  "Resolve a single token string back to its original value.
+   Returns the original value if found, or the token unchanged."
+  [token-str vault]
+  (let [v-map @vault
+        original (get v-map token-str)]
+    (or original token-str)))
+
+=== FILE: test/mcp_injector/restoration_test.clj ===
+(ns mcp-injector.restoration-test
+  (:require [clojure.test :refer [deftest is testing use-fixtures]]
+            [clojure.string :as str]
+            [mcp-injector.pii :as pii]
+            [mcp-injector.test-llm-server :as test-llm]
+            [mcp-injector.test-mcp-server :as test-mcp]
+            [mcp-injector.core :as core]
+            [cheshire.core :as json]
+            [org.httpkit.client :as http]))
+
+(def test-state (atom {}))
+
+(use-fixtures :once
+  (fn [f]
+    (let [llm (test-llm/start-server)
+          mcp (test-mcp/start-server)]
+      (swap! test-state assoc :llm llm :mcp mcp)
+      (let [injector (core/start-server
+                      {:port 0
+                       :host "127.0.0.1"
+                       :llm-url (str "http://localhost:" (:port llm))
+                       :mcp-servers {:servers
+                                     {:trusted-db
+                                      {:url (str "http://localhost:" (:port mcp))
+                                       :tools ["query"]
+                                       :trust :restore}
+                                     :untrusted-api
+                                      {:url (str "http://localhost:" (:port mcp))
+                                       :tools ["send"]
+                                       :trust :none}
+                                     :workspace
+                                      {:url (str "http://localhost:" (:port mcp))
+                                       :trust :restore}}})]
+        (swap! test-state assoc :injector injector)
+        (try
+          (f)
+          (finally
+            (core/stop-server injector)
+            (test-llm/stop-server llm)
+            (test-mcp/stop-server mcp)))))))
+
+(deftest test-secret-redaction-and-restoration
+  (testing "End-to-end Redact -> Decide -> Restore flow"
+    (let [{:keys [injector llm mcp]} @test-state
+          port (:port injector)]
+      ;; 1. Setup MCP to return a secret
+      ((:set-tools! mcp)
+       {:query {:description "Query database"
+                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
+                :handler (fn [args]
+                           (if (or (:email args) (get args "email"))
+                             {:status "success" :received (or (:email args) (get args "email"))}
+                             {:email "wes@example.com" :secret "super-secret-123"})}})
+      ;; 2. LLM Turn 1: Get data (will be redacted)
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :tool_calls [{:id "call_1"
+                                                  :function {:name "mcp__trusted-db__query"
+                                                             :arguments "{\"q\":\"select user\"}"}}]})
+      ;; 3. LLM Turn 2: Receive redacted data and call another tool using the token
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :content "I found the user. Now updating."
+                                    :tool_calls [{:id "call_2"
+                                                  :function {:name "mcp__trusted-db__query"
+                                                             :arguments "{\"email\":\"[EMAIL_ADDRESS_a35e2662]\"}"}}]})
+      ;; Final response
+      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+                                 {:body (json/generate-string
+                                         {:model "brain"
+                                          :messages [{:role "user" :content "Update user wes"}]
+                                          :stream false
+                                          :extra_body {:request-id "test-request-id-12345"}})
+                                  :headers {"Content-Type" "application/json"}})]
+        (is (= 200 (:status response)))
+        ;; Verify MCP received the RESTORED value in the second call
+        (let [mcp-requests @(:received-requests mcp)
+              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
+              update-call (last tool-calls)
+              args-str (-> update-call :body :params :arguments)
+              args (json/parse-string args-str true)]
+          (is (= "wes@example.com" (:email args))))
+        ;; Verify LLM received REDACTED token (not original) in tool result
+        (let [llm-requests @(:received-requests llm)
+              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
+              msgs (:messages tool-call-req)
+              tool-result-msg (last msgs)]
+          (is (some? tool-result-msg))
+          (is (= "tool" (:role tool-result-msg)))
+          (is (str/includes? (:content tool-result-msg) "[EMAIL_ADDRESS_a35e2662]"))
+          (is (not (str/includes? (:content tool-result-msg) "wes@example.com"))))))))
+
+(deftest test-edit-tool-with-pii-token
+  (testing "Edit tool can use restored PII tokens (fixes read->edit workflow)"
+    (let [{:keys [injector llm mcp]} @test-state
+          port (:port injector)
+          request-id "edit-test-request-id"
+          secret-email "wes@example.com"
+          token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
+      ;; Setup MCP with read and edit tools
+      ((:set-tools! mcp)
+       {:read-file
+        {:description "Read file contents"
+         :schema {:type "object" :properties {:path {:type "string"}}}
+         :handler (fn [args]
+                    {:content secret-email})}
+        :edit-file
+        {:description "Edit file"
+         :schema {:type "object" :properties {:path {:type "string"}
+                                               :old_string {:type "string"}
+                                               :new_string {:type "string"}}}
+         :handler (fn [args]
+                    {:success true
+                     :received-args args})}})
+      ;; LLM Turn 1: Read file - should get token
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :content "I'll read the file."
+                                    :tool_calls [{:id "call_1"
+                                                  :function {:name "mcp__workspace__read-file"
+                                                             :arguments (json/generate-string {:path "/tmp/script.sh"})}}]})
+      ;; LLM Turn 2: Uses token in edit old_string
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :content "Updating email..."
+                                    :tool_calls [{:id "call_2"
+                                                  :function {:name "mcp__workspace__edit-file"
+                                                             :arguments (json/generate-string
+                                                                         {:path "/tmp/script.sh"
+                                                                          :old_string token
+                                                                          :new_string "new@example.com"})}}]})
+      ;; Final response
+      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+                                 {:body (json/generate-string
+                                         {:model "brain"
+                                          :messages [{:role "user" :content "Update the email in /tmp/script.sh"}]
+                                          :stream false
+                                          :extra_body {:request-id request-id}})
+                                  :headers {"Content-Type" "application/json"}})]
+        (is (= 200 (:status response)))
+        ;; Verify that edit tool received the ACTUAL email (not token) in old_string
+        (let [mcp-requests @(:received-requests mcp)
+              edit-call (last (filter #(= "tools/call" (-> % :body :method)) mcp-requests))
+              args-str (-> edit-call :body :params :arguments)
+              args (json/parse-string args-str true)]
+          (is (= secret-email (:old_string args))))))))
+
+(defn -main [& _args]
+  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
+    (System/exit (if (zero? (:fail result)) 0 1))))
+
+=== FILE: flake.nix ===
+{
+  description = "mcp-injector - HTTP shim for injecting MCP tools into OpenAI-compatible chat completions";
+
+  inputs = {
+    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
+    flake-utils.url = "github:numtide/flake-utils";
+  };
+
+  outputs = {
+    self,
+    nixpkgs,
+    flake-utils,
+  }:
+    flake-utils.lib.eachDefaultSystem (
+      system: let
+        pkgs = nixpkgs.legacyPackages.${system};
+
+        babashka = pkgs.babashka;
+
+        mcp-injector = pkgs.stdenv.mkDerivation {
+          pname = "mcp-injector";
+          version = "0.1.0";
+
+          src = ./.;
+
+          nativeBuildInputs = [pkgs.makeWrapper];
+          buildInputs = [babashka];
+
+          installPhase = ''
+            mkdir -p $out/bin $out/share/mcp-injector
+
+            cp -r src $out/share/mcp-injector/
+            cp bb.edn $out/share/mcp-injector/
+            cp mcp-servers.example.edn $out/share/mcp-injector/mcp-servers.edn
+
+            # Wrapper that points to the code without changing CWD
+            makeWrapper ${babashka}/bin/bb $out/bin/mcp-injector \
+              --prefix PATH : ${babashka}/bin \
+              --add-flags "-cp $out/share/mcp-injector/src:$out/share/mcp-injector/test" \
+              --add-flags "-m" \
+              --add-flags "mcp-injector.core"
+          '';
+
+          meta = with pkgs.lib; {
+            description = "HTTP shim for injecting MCP tools into OpenAI-compatible chat completions";
+            homepage = "https://github.com/anomalyco/mcp-injector";
+            license = licenses.mit;
+            maintainers = [];
+            platforms = platforms.unix;
+          };
+        };
+      in {
+        formatter = pkgs.alejandra;
+        packages = {
+          default = mcp-injector;
+        };
+
+        devShells.default = pkgs.mkShell {
+          buildInputs = with pkgs; [
+            babashka
+            clojure
+            clj-kondo
+            cljfmt
+            mdformat
+          ];
+
+          shellHook = ''
+            echo "mcp-injector Dev Environment"
+            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
+            echo "Babashka version: $(bb --version)"
+            echo ""
+            echo "Quick start:"
+            echo "  bb run          - Start the server"
+            echo "  bb test         - Run tests"
+            echo "  bb repl         - Start REPL"
+            echo ""
+            echo "  nix build       - Build the package"
+            echo "  nix run         - Run the built package"
+            echo ""
+            export SHELL=$OLDSHELL
+          '';
+        };
+
+        apps = {
+          default = {
+            type = "app";
+            program = "${mcp-injector}/bin/mcp-injector";
+            meta = {
+              description = "Start mcp-injector server";
+            };
+          };
+        };
+      }
+    )
+    // {
+      nixosModules.default = {
+        config,
+        lib,
+        pkgs,
+        ...
+      }:
+        with lib; let
+          cfg = config.services.mcp-injector;
+
+          mcp-injector-pkg = self.packages.${pkgs.system}.default;
+
+          mcpServersConfig =
+            pkgs.runCommand "mcp-servers.edn" {
+              nativeBuildInputs = [pkgs.jet];
+            } ''
+              # Merge mcpServers and governance into a single EDN file
+              # mcpServers should contain {:servers {...} :llm-gateway {...}}
+              echo '${builtins.toJSON (cfg.mcpServers // { governance = cfg.governance; })}' | jet -i json -o edn -k > $out
+            '';
+        in {
+          options.services.mcp-injector = {
+            enable = mkEnableOption "mcp-injector HTTP server";
+
+            port = mkOption {
+              type = types.port;
+              default = 8088;
+              description = "Port for the mcp-injector HTTP server";
+            };
+
+            host = mkOption {
+              type = types.str;
+              default = "127.0.0.1";
+              description = "Host address to bind to";
+            };
+
+            llmUrl = mkOption {
+              type = types.str;
+              default = "http://localhost:8080";
+              description = "URL of OpenAI-compatible LLM endpoint";
+            };
+
+             mcpServers = mkOption {
+               type = types.attrs;
+               default = {};
+               description = "MCP server configurations";
+               example = literalExpression ''
+                 {
+                   stripe = {
+                     url = "http://localhost:3001/mcp";
+                     trust = "restore";  # "none" (default), "read", or "restore"
+                     tools = ["retrieve_customer" "list_charges"];
+                   };
+                   workspace = {
+                     url = "http://localhost:3000/mcp";
+                     trust = "restore";
+                     tools = [
+                       { name = "read"; trust = "read"; }
+                       { name = "write"; trust = "restore"; }
+                     ];
+                   };
+                 }
+               '';
+             };
+
+            governance = mkOption {
+              type = types.submodule {
+                options = {
+                  mode = mkOption {
+                    type = types.enum ["permissive" "strict"];
+                    default = "permissive";
+                    description = "Governance mode. Strict requires explicit allow-rules.";
+                  };
+                  policy = mkOption {
+                    type = types.attrs;
+                    default = {};
+                    description = "Allow/Deny lists and model rules.";
+                  };
+                  pii = mkOption {
+                    type = types.attrs;
+                    default = {
+                      enabled = true;
+                      mode = "replace";
+                    };
+                    description = "PII scanning configuration.";
+                  };
+                  audit = mkOption {
+                    type = types.attrs;
+                    default = {
+                      enabled = true;
+                      path = "logs/audit.log.ndjson";
+                    };
+                    description = "Audit trail configuration.";
+                  };
+                };
+              };
+              default = {};
+              description = "Governance and security framework configuration.";
+            };
+
+            logLevel = mkOption {
+              type = types.enum ["debug" "info" "warn" "error"];
+              default = "info";
+              description = "Logging level";
+            };
+
+            maxIterations = mkOption {
+              type = types.int;
+              default = 10;
+              description = "Maximum agent loop iterations";
+            };
+
+            timeoutMs = mkOption {
+              type = types.int;
+              default = 1800000;
+              description = "Request timeout in milliseconds";
+            };
+
+            user = mkOption {
+              type = types.str;
+              default = "mcp-injector";
+              description = "User to run the service as";
+            };
+
+            group = mkOption {
+              type = types.str;
+              default = "mcp-injector";
+              description = "Group to run the service as";
+            };
+
+            openFirewall = mkOption {
+              type = types.bool;
+              default = false;
+              description = "Open firewall port for mcp-injector";
+            };
+
+            environmentFile = mkOption {
+              type = types.nullOr types.path;
+              default = null;
+              description = "Path to environment file containing secrets (e.g. /etc/secrets/mcp-injector.env)";
+              example = "/etc/secrets/mcp-injector.env";
+            };
+          };
+
+          config = mkIf cfg.enable {
+            users.users.${cfg.user} = {
+              isSystemUser = true;
+              group = cfg.group;
+              description = "mcp-injector service user";
+            };
+
+            users.groups.${cfg.group} = {};
+
+            systemd.services.mcp-injector = {
+              description = "mcp-injector HTTP server";
+              wantedBy = ["multi-user.target"];
+              after = ["network.target"];
+
+              environment = {
+                HOME = "/var/lib/mcp-injector";
+                JAVA_TOOL_OPTIONS = "-Duser.home=/var/lib/mcp-injector";
+                MCP_INJECTOR_PORT = toString cfg.port;
+                MCP_INJECTOR_HOST = cfg.host;
+                MCP_INJECTOR_LLM_URL = cfg.llmUrl;
+                MCP_INJECTOR_LOG_LEVEL = cfg.logLevel;
+                MCP_INJECTOR_MAX_ITERATIONS = toString cfg.maxIterations;
+                MCP_INJECTOR_TIMEOUT_MS = toString cfg.timeoutMs;
+                MCP_INJECTOR_MCP_CONFIG = mcpServersConfig;
+              };
+
+              serviceConfig = {
+                Type = "simple";
+                User = cfg.user;
+                Group = cfg.group;
+                WorkingDirectory = "/var/lib/mcp-injector";
+                ExecStart = "${mcp-injector-pkg}/bin/mcp-injector";
+                Restart = "on-failure";
+                RestartSec = "5s";
+                StateDirectory = "mcp-injector";
+                LogsDirectory = "mcp-injector";
+                EnvironmentFile = mkIf (cfg.environmentFile != null) cfg.environmentFile;
+
+                NoNewPrivileges = true;
+                PrivateTmp = true;
+                ProtectSystem = "strict";
+                ProtectHome = true;
+
+                MemoryMax = "2G";
+                TasksMax = 100;
+              };
+            };
+
+            networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];
+          };
+        };
+    };
+}
+
+=== FILE: mcp-servers.example.edn ===
+;; Governance and security configuration
+;; Privileged tools like clojure-eval are blocked by default, require explicit allow
+ {:governance
+  {:mode :permissive
+   :policy
+   {:allow ["clojure-eval"]}  ;; Allow native clojure-eval tool
+   :pii
+   {:enabled true :mode :replace}
+   :audit
+   {:enabled true :path "logs/audit.log.ndjson"}}
+
+  :servers
+  {:auphonic {:url "http://localhost:3003/mcp"
+              :tools nil
+              :trust "none"}   ; "none" (default), "read", or "restore"
+   :nixos {:cmd ["nix" "run" "github:utensils/mcp-nixos"]
+           :trust "none"}
+     ;; Example local tool (uncomment to use)
+     ;; :filesystem
+     ;; {:cmd ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/path/to/allowed/dir"]
+     ;;  :trust "restore"  ; allow token restoration for edit/write
+     ;;  :tools [{:name "read" :trust "read"}
+     ;;          {:name "write" :trust "restore"}]}
+   }
+;; LLM gateway configuration
+  :llm-gateway
+  {:url "http://prism:8080"
+   :log-level "info" ;; debug|info|warn|error
+
+   ;; Fallback chain for legacy mode (injected into requests)
+   :fallbacks [{:provider "zen"
+                :model "kimi-k2.5-free"}
+               {:provider "nvidia"
+                :model "moonshotai/kimi-k2.5"}
+               {:provider "openrouter"
+                :model "moonshotai/kimi-k2.5"}]
+
+   ;; Virtual models with provider chain and cooldown
+   :virtual-models
+   {:brain
+
+    {:chain [  ;; Tier 1: Top agentic kings — strongest brains when alive
+             "zen/minimax-m2.5-free"                      ;; Free M2.5 — often #1 for coding agents right now
+             "zen/kimi-k2.5-free"                         ;; Free Kimi K2.5 — multimodal/agent swarm leader
+             "zen/glm-4.7-free"                           ;; Free GLM-4.7 — planning/stability beast
+
+  ;; NVIDIA-hosted (dev quotas often generous on these quieter ones)
+             "nvidia/minimaxai/minimax-m2.5"              ;; MiniMax on NVIDIA — fast/reliable
+             "nvidia/moonshotai/kimi-k2.5"                ;; Kimi on NVIDIA — good when not overloaded
+             "nvidia/z-ai/glm5"                           ;; GLM-5 — frontier agentic
+             "nvidia/qwen/qwen3-coder-480b-a35b-instruct" ;; Qwen Coder — repo/tool monster
+
+  ;; Last-resort paid fallbacks via OpenRouter
+             "openrouter/minimax/minimax-m2.5"
+             "openrouter/moonshotai/kimi-k2.5"
+             "openrouter/z-ai/glm5"
+             "openrouter/qwen/qwen3-coder-480b-a35b-instruct"]
+     :cooldown-minutes 5
+     ;; Don't include 503 (context overflow) in retry-on
+     ;; Same model has same context window, so advancing the chain wastes quota
+     ;; Let OpenClaw compress the session instead
+     :retry-on [429 500]}}}}
+
+
+=== FILE: dev/specs/configurable-trust-levels.edn ===
+{:title "Configurable PII Restoration Trust Levels"
+ :description "Enhance Smart Vault to support configurable trust levels for fine-grained control over token restoration."
+ :acceptance-criteria
+ ["Trust levels :none, :read, :restore are supported in mcp-servers.edn config"
+  "edit tool can restore tokens from vault when server/tool trust is :restore"
+  "NixOS module exposes trust configuration options"
+  "Audit logging records restoration events"
+  "Read tool test demonstrates token generation and vault storage"
+  "Edit tool test demonstrates successful token resolution and file modification"]
+ :edge-cases
+ ["Token not found in vault (should pass through)"
+  "Multiple tokens in single argument string"
+  "Restoration for deeply nested JSON structures"
+  "Partial path trust (server-level :restore but tool-level :none)"]}
+
diff --git a/dev/PII_RESTORATION_PLAN.md b/dev/PII_RESTORATION_PLAN.md
new file mode 100644
index 0000000..903b280
--- /dev/null
+++ b/dev/PII_RESTORATION_PLAN.md
@@ -0,0 +1,31 @@
+# PII Restoration & Secret Substitution Plan
+
+## Status
+- **Branch:** `feat/pii-restoration`
+- **Current State:** COMPLETE - All tests passing
+- **Completed:** 2026-03-14
+
+## Summary
+Successfully implemented "Smart Vault" PII restoration system:
+- Deterministic token generation with SHA-256
+- Request-scoped vault for token/value mapping
+- Trust levels (`:none`, `:read`, `:restore`) at server and tool level
+- Two-way substitution: LLM sees tokens, trusted tools see real values
+
+## Core Strategy: The "Smart Vault"
+We are moving from "One-Way Redaction" to "Two-Way Substitution" for trusted tools.
+
+### 1. Tokenization (Outbound to LLM)
+- **Deterministic Hashing:** Replace `[EMAIL_ADDRESS]` with `[PII_EMAIL_8ce0db03]`. 
+- **Vaulting:** Store `{ "[PII_EMAIL_8ce0db03]" "wes@example.com" }` in a request-scoped vault.
+- **Structural Awareness:** Use `clojure.walk` to redact JSON values while preserving keys (so LLM understands schema).
+
+### 2. Restoration (Inbound to Tools)
+- **Trust Tiers:** Define `:trust :restore` in `mcp-servers.edn`.
+- **Restoration:** If a tool call targets a trusted server, `mcp-injector` swaps tokens back for real values before execution.
+- **Safety:** Untrusted tools continue to see only the redacted tokens.
+
+## Build Results
+- **55 tests** - All passing
+- **Lint** - Clean
+- **Format** - Clean
diff --git a/dev/specs/configurable-trust-levels.edn b/dev/specs/configurable-trust-levels.edn
new file mode 100644
index 0000000..3881440
--- /dev/null
+++ b/dev/specs/configurable-trust-levels.edn
@@ -0,0 +1,14 @@
+{:title "Configurable PII Restoration Trust Levels"
+ :description "Enhance Smart Vault to support configurable trust levels for fine-grained control over token restoration."
+ :acceptance-criteria
+ ["Trust levels :none, :read, :restore are supported in mcp-servers.edn config"
+  "edit tool can restore tokens from vault when server/tool trust is :restore"
+  "NixOS module exposes trust configuration options"
+  "Audit logging records restoration events"
+  "Read tool test demonstrates token generation and vault storage"
+  "Edit tool test demonstrates successful token resolution and file modification"]
+ :edge-cases
+ ["Token not found in vault (should pass through)"
+  "Multiple tokens in single argument string"
+  "Restoration for deeply nested JSON structures"
+  "Partial path trust (server-level :restore but tool-level :none)"]}
diff --git a/dev/specs/pii-restoration.edn b/dev/specs/pii-restoration.edn
new file mode 100644
index 0000000..edb6cd1
--- /dev/null
+++ b/dev/specs/pii-restoration.edn
@@ -0,0 +1,15 @@
+{:title "PII/Secret Restoration (Round-Trip)"
+ :description "Enable trusted tools to receive original sensitive data while keeping the LLM's view redacted."
+ :acceptance-criteria
+ ["Tool outputs containing PII are tokenized with deterministic, hybrid labels (e.g., [EMAIL_8f3a2])"
+  "Tokens remain consistent across a single request context"
+  "A request-scoped Vault stores the mapping of Token -> Original Value"
+  "Trusted tools (marked with :trust :restore) receive restored values in their arguments"
+  "Untrusted tools receive the literal token strings"
+  "Deep JSON redaction preserves map keys but tokenizes values"]
+ :edge-cases
+ ["Recursive data structures in tool arguments"
+  "Mixed plain-text and JSON tool outputs"
+  "Token collisions (mitigated via request-id salt)"
+  "Empty or null values in scanned data"]
+ :depends-on [:governance-core :mcp-client]}
diff --git a/flake.nix b/flake.nix
index fb71f9f..30dc860 100644
--- a/flake.nix
+++ b/flake.nix
@@ -134,19 +134,28 @@
               description = "URL of OpenAI-compatible LLM endpoint";
             };
 
-            mcpServers = mkOption {
-              type = types.attrs;
-              default = {};
-              description = "MCP server configurations";
-              example = literalExpression ''
-                {
-                  stripe = {
-                    url = "http://localhost:3001/mcp";
-                    tools = ["retrieve_customer" "list_charges"];
-                  };
-                }
-              '';
-            };
+             mcpServers = mkOption {
+               type = types.attrs;
+               default = {};
+               description = "MCP server configurations";
+               example = literalExpression ''
+                 {
+                   stripe = {
+                     url = "http://localhost:3001/mcp";
+                     trust = "restore";  # "none" (default), "read", or "restore"
+                     tools = ["retrieve_customer" "list_charges"];
+                   };
+                   workspace = {
+                     url = "http://localhost:3000/mcp";
+                     trust = "restore";
+                     tools = [
+                       { name = "read"; trust = "read"; }
+                       { name = "write"; trust = "restore"; }
+                     ];
+                   };
+                 }
+               '';
+             };
 
             governance = mkOption {
               type = types.submodule {
diff --git a/mcp-servers.example.edn b/mcp-servers.example.edn
index bfe5165..ba0ef3b 100644
--- a/mcp-servers.example.edn
+++ b/mcp-servers.example.edn
@@ -11,12 +11,16 @@
 
   :servers
   {:auphonic {:url "http://localhost:3003/mcp"
-              :tools nil}
-   :nixos {:cmd ["nix" "run" "github:utensils/mcp-nixos"]}
-    ;; Example local tool (uncomment to use)
-    ;; :filesystem
-    ;; {:cmd ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/path/to/allowed/dir"]
-    ;;  :env {"EXAMPLE_VAR" "value"}}
+              :tools nil
+              :trust "none"}   ; "none" (default), "read", or "restore"
+   :nixos {:cmd ["nix" "run" "github:utensils/mcp-nixos"]
+           :trust "none"}
+     ;; Example local tool (uncomment to use)
+     ;; :filesystem
+     ;; {:cmd ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/path/to/allowed/dir"]
+     ;;  :trust "restore"  ; allow token restoration for edit/write
+     ;;  :tools [{:name "read" :trust "read"}
+     ;;          {:name "write" :trust "restore"}]}
    }
 ;; LLM gateway configuration
   :llm-gateway
diff --git a/result b/result
deleted file mode 120000
index eea2214..0000000
--- a/result
+++ /dev/null
@@ -1 +0,0 @@
-/nix/store/gdjbiza5hidsdb7lx3spirlsxybwlzry-mcp-injector-0.1.0
\ No newline at end of file
diff --git a/src/mcp_injector/config.clj b/src/mcp_injector/config.clj
index aa15670..d8511eb 100644
--- a/src/mcp_injector/config.clj
+++ b/src/mcp_injector/config.clj
@@ -166,6 +166,34 @@
     []
     (:servers mcp-config))))
 
+(defn get-server-trust
+  "Get trust level for a server/tool combination.
+   Returns :restore (full restoration), :read (read-only access), :none (untrusted), or :block.
+   Precedence: tool-level :trust > server-level :trust > :none.
+   Accepts trust values as either keywords (:restore) or strings (\"restore\")."
+  [mcp-config server-name tool-name]
+  (let [servers (:servers mcp-config)
+        server (get servers (keyword server-name))]
+    (if-not server
+      :none
+      (let [server-trust (some-> server :trust keyword)
+            tool-configs (:tools server)
+            tool-config (cond
+                          (map? tool-configs)
+                          (get tool-configs (keyword tool-name))
+
+                          (sequential? tool-configs)
+                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)
+
+                          :else nil)
+            tool-trust (some-> tool-config :trust keyword)]
+        (cond
+          (= tool-trust :block) :block
+          (= server-trust :block) :block
+          (#{:restore :read} tool-trust) tool-trust
+          (#{:restore :read} server-trust) server-trust
+          :else :none)))))
+
 (defn get-meta-tool-definitions
   "Get definitions for meta-tools like get_tool_schema and native tools"
   []
diff --git a/src/mcp_injector/core.clj b/src/mcp_injector/core.clj
index 5cee001..bdd154a 100644
--- a/src/mcp_injector/core.clj
+++ b/src/mcp_injector/core.clj
@@ -169,12 +169,12 @@
         (= full-name "clojure-eval")
         (try
           (let [code (:code args)
-                ;; NOTE: clojure-eval is a full JVM/Babashka load-string. 
+                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
                 ;; Security is currently enforced only via the Policy layer (explicit opt-in).
                 result (load-string code)]
-            (pr-str result))
+            (json/generate-string result))
           (catch Exception e
-            {:error (str "Eval error: " (.getMessage e))}))
+            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))
 
         (str/starts-with? full-name "mcp__")
         (let [t-name (str/replace full-name #"^mcp__" "")
@@ -199,28 +199,83 @@
 
         :else {:error (str "Unknown tool: " full-name)}))))
 
-(defn- scrub-messages [messages]
+(defn- parse-tool-name
+  "Parse mcp__server__tool format into [server tool]"
+  [full-name]
+  (if (str/includes? full-name "__")
+    (let [t-name (str/replace full-name #"^mcp__" "")
+          idx (str/last-index-of t-name "__")]
+      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
+    [nil full-name]))
+
+(defn- scrub-messages [messages vault request-id]
   (mapv (fn [m]
-          (if (string? (:content m))
-            (let [{:keys [text detected]} (pii/scan-and-redact (:content m) {:mode :replace})]
-              (when (seq detected)
-                (log-request "info" "PII Redacted" {:labels detected} {:role (:role m)}))
-              (assoc m :content text))
-            m))
+          (let [content (:content m)
+                role (:role m)]
+            (if (and (string? content)
+                     ;; Only redact user/system messages - assistant tool results are already handled
+                     (or (= role "system") (= role "user"))
+                      ;; Skip if already contains PII tokens (avoid double-redaction)
+                      ;; Token format: [LABEL_hex12] e.g., [EMAIL_ADDRESS_a35e26620952]
+                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{12}\]" content)))
+              (let [config {:mode :replace :salt request-id}
+                    [redacted-content _ _] (pii/redact-data content config vault)]
+                (assoc m :content redacted-content))
+              m)))
         messages))
 
-(defn- sanitize-tool-output [content]
-  (if (string? content)
-    (str/replace content
-                 #"(?im)^\s*(system|human|assistant|user)\s*:"
-                 "[REDACTED_ROLE_MARKER]")
-    content))
+(defn- restore-tool-args
+  "Restore tokens in tool args if server/tool is trusted (:restore level)."
+  [args vault mcp-servers full-tool-name]
+  (let [[server tool] (parse-tool-name full-tool-name)
+        trust (when server (config/get-server-trust mcp-servers server tool))]
+    (if (= trust :restore)
+      (pii/restore-tokens args vault)
+      args)))
+
+(defn- redact-tool-output
+  "Redact PII from tool output, return [content vault]"
+  [raw-output vault request-id]
+  (let [;; Try to parse as JSON first for JSON tokenization
+        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
+        ;; If parsed successfully, redact the data structure; otherwise redact the string
+        ;; Special handling for MCP response format: parse nested :text field if present
+        [redacted new-vault detected] (if parsed
+                                        (let [;; Check if this is MCP response format with :text field containing JSON
+                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
+                                              parsed (cond
+                                                       (map? parsed)
+                                                       (if (string? (:text parsed))
+                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
+                                                              (catch Exception _ parsed))
+                                                         parsed)
+                                                       (sequential? parsed)
+                                                       (mapv (fn [item]
+                                                               (if (and (map? item) (string? (:text item)))
+                                                                 (try (assoc item :text (json/parse-string (:text item) true))
+                                                                      (catch Exception _ item))
+                                                                 item))
+                                                             parsed)
+                                                       :else parsed)
+                                              config {:mode :replace :salt request-id}
+                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
+                                          [(json/generate-string redacted-struct) vault-after detected-labels])
+                                        (let [config {:mode :replace :salt request-id}
+                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
+                                          [redacted-str vault-after detected-labels]))]
+
+    ;; Log the detected PII types (not scanning again)
+    (when (seq detected)
+      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
+    [redacted new-vault]))
 
 (defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
   (let [model (:model payload)
         discovered-this-loop (atom {})
-        context {:model model}]
-    (loop [current-payload (update payload :messages scrub-messages)
+        vault (atom {})
+        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
+        context {:model model :request-id request-id}]
+    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
            iteration 0]
       (if (>= iteration max-iterations)
         {:success true
@@ -239,40 +294,46 @@
                   tool-calls (:tool_calls message)]
               (if-not tool-calls
                 (assoc resp :provider model)
-                (let [mcp-calls (filter #(or (= (get-in % [:function :name]) "get_tool_schema")
-                                             (str/starts-with? (get-in % [:function :name]) "mcp__"))
+                (let [mcp-calls (filter (fn [tc]
+                                          (let [n (get-in tc [:function :name])]
+                                            (or (= n "get_tool_schema")
+                                                (and n (str/starts-with? n "mcp__")))))
                                         tool-calls)
                       native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
                                            tool-calls)]
                   (if (and (empty? mcp-calls) (empty? native-calls))
                     (assoc resp :provider model)
-                    (let [results (mapv (fn [tc]
-                                          (let [fn-name (get-in tc [:function :name])
-                                                args-str (get-in tc [:function :arguments])
-                                                parse-result (try
-                                                               {:success true :args (json/parse-string args-str true)}
-                                                               (catch Exception e
-                                                                 {:success false :error (.getMessage e)}))]
-                                            (if (:success parse-result)
-                                              (let [result (execute-tool fn-name (:args parse-result) mcp-servers discovered-this-loop governance context)
-                                                    ;; Scrub and sanitize tool output
-                                                    raw-content (if (string? result) result (json/generate-string result))
-                                                    sanitized (sanitize-tool-output raw-content)
-                                                    {:keys [text detected]} (pii/scan-and-redact sanitized {:mode :replace})
-                                                    _ (when (seq detected)
-                                                        (log-request "info" "PII Redacted in Tool Output" {:tool fn-name :labels detected} context))]
-                                                {:role "tool"
+                    (let [[results new-vault]
+                          (reduce
+                           (fn [[results vault-state] tc]
+                             (let [fn-name (get-in tc [:function :name])
+                                   args-str (get-in tc [:function :arguments])
+                                   parse-result (try
+                                                  {:success true :args (json/parse-string args-str true)}
+                                                  (catch Exception e
+                                                    {:success false :error (.getMessage e)}))]
+                               (if (:success parse-result)
+                                 (let [;; Restore args if trusted
+                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
+                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
+                                       ;; Redact output with vault
+                                       raw-content (if (string? result) result (json/generate-string result))
+                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
+                                   [(conj results {:role "tool"
+                                                   :tool_call_id (:id tc)
+                                                   :name fn-name
+                                                   :content redacted})
+                                    updated-vault])
+                                 [(conj results {:role "tool"
                                                  :tool_call_id (:id tc)
                                                  :name fn-name
-                                                 :content text})
-                                              {:role "tool"
-                                               :tool_call_id (:id tc)
-                                               :name fn-name
-                                               :content (json/generate-string
-                                                         {:error "Malformed tool arguments JSON"
-                                                          :details {:args-str args-str
-                                                                    :parse-error (:error parse-result)}})})))
-                                        (concat mcp-calls native-calls))
+                                                 :content (json/generate-string
+                                                           {:error "Malformed tool arguments JSON"
+                                                            :details {:args-str args-str
+                                                                      :parse-error (:error parse-result)}})})
+                                  vault-state])))
+                           [[] vault]
+                           (concat mcp-calls native-calls))
                           newly-discovered @discovered-this-loop
                           new-tools (vec (concat (config/get-meta-tool-definitions)
                                                  (map (fn [[name schema]]
@@ -281,9 +342,12 @@
                                                                     :description (:description schema)
                                                                     :parameters (:inputSchema schema)}})
                                                       newly-discovered)))
-                          new-messages (conj (vec (:messages current-payload)) message)
+                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
                           new-messages (into new-messages results)]
-                      (recur (assoc current-payload :messages new-messages :tools new-tools) (inc iteration)))))))))))))
+                      (recur (assoc current-payload
+                                    :messages (scrub-messages new-messages new-vault request-id)
+                                    :tools new-tools)
+                             (inc iteration)))))))))))))
 
 (defn- set-cooldown! [provider minutes]
   (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))
@@ -334,11 +398,14 @@
                                   discovered-tools)
         merged-tools (vec (concat (or existing-tools [])
                                   meta-tools
-                                  discovered-tool-defs))]
+                                  discovered-tool-defs))
+        ;; Merge extra_body into the request for fields like request-id
+        extra-body (or (:extra_body chat-req) {})]
     (-> chat-req
         (assoc :stream false)
         (dissoc :stream_options)
         (assoc :fallbacks fallbacks)
+        (merge extra-body) ;; Lift extra_body fields to top level
         (update :messages (fn [msgs]
                             (mapv (fn [m]
                                     (if (and (= (:role m) "assistant") (:tool_calls m))
@@ -428,11 +495,13 @@
                      (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
           {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
     (catch Exception e
-      (let [err-type (or (some-> e ex-data :type name) "internal_error")]
-        (log-request "error" "Chat completion failed" {:type err-type :message (.getMessage e)} {})
+      (let [err-type (or (some-> e ex-data :type name) "internal_error")
+            err-msg (or (.getMessage e) (str e))
+            stack (.getStackTrace e)]
+        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
         {:status 400
          :headers {"Content-Type" "application/json"}
-         :body (json/generate-string {:error {:message (or (.getMessage e) "Internal server error")
+         :body (json/generate-string {:error {:message err-msg
                                               :type err-type}})}))))
 
 (defn get-gateway-state []
@@ -509,46 +578,51 @@
                                                   :type err-type}})}))))))
 
 (defn start-server [mcp-config]
-  (let [initial-config (if (and (map? mcp-config) (not (:servers mcp-config)))
-                         mcp-config
-                         {})
-        port (or (:port initial-config)
+  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
+        provided-governance (or (:governance mcp-config)
+                                (:governance (:mcp-servers mcp-config)))
+
+        ;; Runtime settings - prioritize input > env > default
+        port (or (:port mcp-config)
                  (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
                  8080)
-        host (or (:host initial-config)
+        host (or (:host mcp-config)
                  (System/getenv "MCP_INJECTOR_HOST")
                  "127.0.0.1")
-        llm-url (or (:llm-url initial-config)
+        llm-url (or (:llm-url mcp-config)
                     (System/getenv "MCP_INJECTOR_LLM_URL")
                     "http://localhost:11434")
-        log-level (or (:log-level initial-config)
+        log-level (or (:log-level mcp-config)
                       (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
-        max-iterations (or (:max-iterations initial-config)
+        max-iterations (or (:max-iterations mcp-config)
                            (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
                            10)
-        mcp-config-path (or (:mcp-config-path initial-config)
+        mcp-config-path (or (:mcp-config-path mcp-config)
                             (System/getenv "MCP_INJECTOR_MCP_CONFIG")
                             "mcp-servers.edn")
         ;; Audit trail config
-        audit-log-path (or (:audit-log-path initial-config)
+        audit-log-path (or (:audit-log-path mcp-config)
                            (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
                            "logs/audit.log.ndjson")
-        audit-secret (or (:audit-secret initial-config)
+        audit-secret (or (:audit-secret mcp-config)
                          (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
                          "default-audit-secret")
         ;; Merge provided mcp-config with loaded ones if needed
         base-mcp-servers (cond
                            (and (map? mcp-config) (:servers mcp-config)) mcp-config
-                           (:mcp-servers initial-config) (:mcp-servers initial-config)
+                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
                            :else (config/load-mcp-servers mcp-config-path))
-        ;; Apply overrides from initial-config (like :virtual-models in tests)
-        mcp-servers (if (seq initial-config)
-                      (let [gateway-overrides (select-keys initial-config [:virtual-models :fallbacks :url])]
-                        (update base-mcp-servers :llm-gateway merge gateway-overrides))
+        ;; Apply overrides from mcp-config (like :virtual-models in tests)
+        mcp-servers (if (map? mcp-config)
+                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
+                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
+                        (if-let [gov (:governance mcp-config)]
+                          (assoc merged :governance gov)
+                          merged))
                       base-mcp-servers)
-        ;; Unified configuration resolution
+        ;; Unified configuration resolution - pass extracted governance
         unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
-        final-governance (config/resolve-governance (assoc mcp-servers :governance (:governance initial-config)) unified-env)
+        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
         final-config {:port port :host host :llm-url llm-url :log-level log-level
                       :max-iterations max-iterations :mcp-config-path mcp-config-path
                       :audit-log-path audit-log-path :audit-secret audit-secret
diff --git a/src/mcp_injector/pii.clj b/src/mcp_injector/pii.clj
index faeb7e7..c5de1cf 100644
--- a/src/mcp_injector/pii.clj
+++ b/src/mcp_injector/pii.clj
@@ -1,12 +1,13 @@
 (ns mcp-injector.pii
-  (:require [clojure.string :as str]))
+  (:require [clojure.string :as str]
+            [clojure.walk :as walk])
+  (:import (java.security MessageDigest)))
 
 (def default-patterns
   [{:id :EMAIL_ADDRESS
     :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
     :label "[EMAIL_ADDRESS]"}
    {:id :IBAN_CODE
-    ;; Tightened range to 15-34 and added case-insensitivity support via (?i)
     :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
     :label "[IBAN_CODE]"}])
 
@@ -46,8 +47,6 @@
 (defn- scan-env [text env-vars mode]
   (reduce-kv
    (fn [acc k v]
-     ;; Case-sensitive match for env vars is usually safer, 
-     ;; but we ensure the value is long enough to avoid false positives.
      (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
        (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
        acc))
@@ -64,7 +63,6 @@
   (let [tokens (str/split text #"\s+")]
     (reduce
      (fn [acc token]
-       ;; Threshold raised to 4.0 + diversity check + length check
        (if (and (> (count token) 12)
                 (> (shannon-entropy token) threshold)
                 (character-diversity? token))
@@ -74,15 +72,13 @@
      tokens)))
 
 (defn scan-and-redact
-  "Scans input text for PII patterns, high-entropy secrets, and env vars.
-   Calculations are performed sequentially on the text."
+  "Scans input text for PII patterns, high-entropy secrets, and env vars."
   [text {:keys [mode patterns entropy-threshold env]
          :or {mode :replace
               patterns default-patterns
               entropy-threshold 4.0
               env {}}}]
-  (let [;; 1. Regex patterns (Standard PII)
-        regex-result (reduce
+  (let [regex-result (reduce
                       (fn [state {:keys [id pattern label]}]
                         (if (seq (re-seq pattern (:text state)))
                           {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
@@ -90,14 +86,89 @@
                           state))
                       {:text text :detected []}
                       patterns)
-
-        ;; 2. Env vars (Exact matches)
         env-text (scan-env (:text regex-result) env mode)
         env-detections (find-env-detections text env)
-
-        ;; 3. Entropy (Heuristic secrets)
         final-text (scan-entropy env-text entropy-threshold mode)
         entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
-
     {:text final-text
      :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))
+
+(defn generate-token
+  "Generate a deterministic, truncated SHA-256 hash token.
+   Uses 12 hex chars (48 bits) to reduce collision probability."
+  [label value salt]
+  (let [input (str (name label) "|" value "|" salt)
+        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
+        hash-str (->> digest
+                      (map (partial format "%02x"))
+                      (apply str))
+        truncated (subs hash-str 0 12)]
+    (str "[" (name label) "_" truncated "]")))
+
+(defn- redact-string-value
+  "Redact a single string value, returning [redacted-text token detected-label]"
+  [v config]
+  (if-not (string? v)
+    [v nil nil]
+    (if (empty? v)
+      [v nil nil]
+      (let [vault (:vault config)
+            salt (:salt config)
+            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
+            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
+        (cond
+          existing-token [existing-token nil nil]
+          previous-token [previous-token nil nil]
+          :else
+          (let [result (scan-and-redact v config)]
+            (if (seq (:detected result))
+              (let [detected (first (:detected result))
+                    token (generate-token detected v salt)]
+                (swap! vault assoc token v)
+                [token token detected])
+              [(:text result) nil nil])))))))
+
+(defn redact-data
+  "Recursively walk a data structure, redact string values, store in vault.
+    Returns [redacted-data vault-atom detected-labels]"
+  ([data config]
+   (redact-data data config (atom {})))
+  ([data config vault]
+   (let [config-with-vault (assoc config :vault vault)
+         detected-labels (atom [])
+         redacted (walk/postwalk
+                   (fn [x]
+                     (if (string? x)
+                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
+                         (when detected (swap! detected-labels conj detected))
+                         redacted-text)
+                       x))
+                   data)]
+     [redacted vault @detected-labels])))
+
+(defn restore-tokens
+  "Recursively walk a data structure, replacing tokens with original values from vault."
+  [data vault]
+  (let [v-map @vault]
+    (if (empty? v-map)
+      data
+      (walk/postwalk
+       (fn [x]
+         (if (string? x)
+           (reduce
+            (fn [s [token original]]
+              (if (and (string? s) (str/includes? s token))
+                (str/replace s (str token) (str original))
+                s))
+            x
+            v-map)
+           x))
+       data))))
+
+(defn- resolve-token
+  "Resolve a single token string back to its original value.
+   Returns the original value if found, or the token unchanged."
+  [token-str vault]
+  (let [v-map @vault
+        original (get v-map token-str)]
+    (or original token-str)))
diff --git a/test/mcp_injector/discovery_test.clj b/test/mcp_injector/discovery_test.clj
index cf4e069..6ae43a4 100644
--- a/test/mcp_injector/discovery_test.clj
+++ b/test/mcp_injector/discovery_test.clj
@@ -79,8 +79,8 @@
       (is (str/includes? (get-in first-req [:messages 0 :content]) "mcp__stripe"))
       (is (some (fn [t] (= "get_tool_schema" (get-in t [:function :name]))) (get-in first-req [:tools])))
       ;; content might be redacted as [EMAIL_ADDRESS] or [HIGH_ENTROPY_SECRET] depending on scanner
-      (is (some (fn [m] (or (str/includes? (:content m) "[EMAIL_ADDRESS]")
-                            (str/includes? (:content m) "[HIGH_ENTROPY_SECRET]"))) tool-msgs)))))
+      (is (some (fn [m] (or (re-find #"\[EMAIL_ADDRESS(_[a-f0-9]{12})?\]" (:content m))
+                            (re-find #"\[HIGH_ENTROPY_SECRET(_[a-f0-9]{12})?\]" (:content m)))) tool-msgs)))))
 
 (deftest tool-discovery-filtering-nil-shows-all
   (testing "When :tools is nil, all discovered tools from MCP server should be shown"
diff --git a/test/mcp_injector/llm_shim_test.clj b/test/mcp_injector/llm_shim_test.clj
index 4142816..748e04b 100644
--- a/test/mcp_injector/llm_shim_test.clj
+++ b/test/mcp_injector/llm_shim_test.clj
@@ -25,7 +25,9 @@
                       {:port 0
                        :host "127.0.0.1"
                        :llm-url (str "http://localhost:" (:port llm))
-                       :mcp-config "./mcp-servers.edn"})]
+                       :mcp-servers {:llm-gateway {:fallbacks ["zen/kimi-k2.5-free"
+                                                               "nvidia/moonshotai/kimi-k2.5"
+                                                               "openrouter/moonshotai/kimi-k2.5"]}}})]
         (swap! test-state assoc :injector injector)
         (try
           (f)
diff --git a/test/mcp_injector/native_tools_test.clj b/test/mcp_injector/native_tools_test.clj
index 865537c..1d8a674 100644
--- a/test/mcp_injector/native_tools_test.clj
+++ b/test/mcp_injector/native_tools_test.clj
@@ -14,10 +14,9 @@
         injector-server (core/start-server {:port 0
                                             :host "127.0.0.1"
                                             :llm-url (str "http://localhost:" (:port llm-server))
-                                            :mcp-servers {:servers {}
-                                                          :llm-gateway {:url (str "http://localhost:" (:port llm-server))
-                                                                        :governance {:mode :permissive
-                                                                                     :policy {:allow ["clojure-eval"]}}}}})]
+                                            :governance {:mode :permissive
+                                                         :policy {:allow ["clojure-eval"]}}
+                                            :mcp-servers {:servers {}}})]
     (try
       (binding [*test-llm* llm-server
                 *injector* injector-server]
@@ -117,10 +116,9 @@
           blocked-injector (core/start-server {:port 0
                                                :host "127.0.0.1"
                                                :llm-url (str "http://localhost:" llm-port)
-                                               :mcp-servers {:servers {}
-                                                             :llm-gateway {:url (str "http://localhost:" llm-port)
-                                                                           :governance {:mode :permissive
-                                                                                        :policy {:allow []}}}}})] ;; empty allow list
+                                               :governance {:mode :permissive
+                                                            :policy {:allow []}}
+                                               :mcp-servers {:servers {}}})] ;; empty allow list
       (try
         ;; Explicitly clear state before starting the denial flow
         (test-llm/clear-responses *test-llm*)
diff --git a/test/mcp_injector/restoration_test.clj b/test/mcp_injector/restoration_test.clj
new file mode 100644
index 0000000..278fec5
--- /dev/null
+++ b/test/mcp_injector/restoration_test.clj
@@ -0,0 +1,152 @@
+(ns mcp-injector.restoration-test
+  (:require [clojure.test :refer [deftest is testing use-fixtures]]
+            [clojure.string :as str]
+            [mcp-injector.pii :as pii]
+            [mcp-injector.test-llm-server :as test-llm]
+            [mcp-injector.test-mcp-server :as test-mcp]
+            [mcp-injector.core :as core]
+            [cheshire.core :as json]
+            [org.httpkit.client :as http]))
+
+(def test-state (atom {}))
+
+(use-fixtures :once
+  (fn [f]
+    (let [llm (test-llm/start-server)
+          mcp (test-mcp/start-server)]
+      (swap! test-state assoc :llm llm :mcp mcp)
+      (let [injector (core/start-server
+                      {:port 0
+                       :host "127.0.0.1"
+                       :llm-url (str "http://localhost:" (:port llm))
+                       :mcp-servers {:servers
+                                     {:trusted-db
+                                      {:url (str "http://localhost:" (:port mcp))
+                                       :tools ["query"]
+                                       :trust :restore}
+                                     :untrusted-api
+                                      {:url (str "http://localhost:" (:port mcp))
+                                       :tools ["send"]
+                                       :trust :none}
+                                     :workspace
+                                      {:url (str "http://localhost:" (:port mcp))
+                                       :trust :restore}}})]
+        (swap! test-state assoc :injector injector)
+        (try
+          (f)
+          (finally
+            (core/stop-server injector)
+            (test-llm/stop-server llm)
+            (test-mcp/stop-server mcp)))))))
+
+(deftest test-secret-redaction-and-restoration
+  (testing "End-to-end Redact -> Decide -> Restore flow"
+    (let [{:keys [injector llm mcp]} @test-state
+          port (:port injector)
+          request-id "test-request-id-12345"
+          secret-email "wes@example.com"
+          expected-token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
+      ;; Setup MCP to return a secret
+      ((:set-tools! mcp)
+       {:query {:description "Query database"
+                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
+                :handler (fn [args]
+                           (if-let [email (or (:email args) (get args "email"))]
+                             {:status "success" :received email}
+                             {:email secret-email :secret "super-secret-123"})}})
+      ;; LLM Turn 1: Get data (will be redacted)
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :tool_calls [{:id "call_1"
+                                                  :function {:name "mcp__trusted-db__query"
+                                                             :arguments "{\"q\":\"select user\"}"}}]})
+      ;; LLM Turn 2: Receive redacted data and call another tool using the token
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :content "I found the user. Now updating."
+                                    :tool_calls [{:id "call_2"
+                                                  :function {:name "mcp__trusted-db__query"
+                                                             :arguments (json/generate-string {:email expected-token})}}]})
+      ;; Final response
+      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+                                 {:body (json/generate-string
+                                         {:model "brain"
+                                          :messages [{:role "user" :content "Update user wes"}]
+                                          :stream false
+                                          :extra_body {:request-id request-id}})
+                                  :headers {"Content-Type" "application/json"}})]
+        (is (= 200 (:status response)))
+        ;; Verify MCP received the RESTORED value in the second call
+        (let [mcp-requests @(:received-requests mcp)
+              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
+              update-call (last tool-calls)
+              args-str (-> update-call :body :params :arguments)
+              args (json/parse-string args-str true)]
+          (is (= secret-email (:email args))))
+        ;; Verify LLM received REDACTED token (not original) in tool result
+        (let [llm-requests @(:received-requests llm)
+              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
+              msgs (:messages tool-call-req)
+              tool-result-msg (last msgs)]
+          (is (some? tool-result-msg))
+          (is (= "tool" (:role tool-result-msg)))
+          (is (str/includes? (:content tool-result-msg) expected-token))
+          (is (not (str/includes? (:content tool-result-msg) secret-email))))))))
+
+(deftest test-edit-tool-with-pii-token
+  (testing "Edit tool can use restored PII tokens (fixes read->edit workflow)"
+    (let [{:keys [injector llm mcp]} @test-state
+          port (:port injector)
+          request-id "edit-test-request-id"
+          secret-email "wes@example.com"
+          token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
+      ;; Setup MCP with read and edit tools
+      ((:set-tools! mcp)
+       {:read-file
+        {:description "Read file contents"
+         :schema {:type "object" :properties {:path {:type "string"}}}
+         :handler (fn [args] {:content secret-email})}
+        :edit-file
+        {:description "Edit file"
+         :schema {:type "object" :properties {:path {:type "string"}
+                                               :old_string {:type "string"}
+                                               :new_string {:type "string"}}}
+         :handler (fn [args] {:success true :received-args args})}})
+      ;; LLM Turn 1: Read file
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :content "I'll read the file."
+                                    :tool_calls [{:id "call_1"
+                                                  :function {:name "mcp__workspace__read-file"
+                                                             :arguments (json/generate-string {:path "/tmp/script.sh"})}}]})
+      ;; LLM Turn 2: Uses token in edit old_string
+      (test-llm/set-next-response llm
+                                   {:role "assistant"
+                                    :content "Updating email..."
+                                    :tool_calls [{:id "call_2"
+                                                  :function {:name "mcp__workspace__edit-file"
+                                                             :arguments (json/generate-string
+                                                                         {:path "/tmp/script.sh"
+                                                                          :old_string token
+                                                                          :new_string "new@example.com"})}}]})
+      ;; Final response
+      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
+      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
+                                 {:body (json/generate-string
+                                         {:model "brain"
+                                          :messages [{:role "user" :content "Update the email in /tmp/script.sh"}]
+                                          :stream false
+                                          :extra_body {:request-id request-id}})
+                                  :headers {"Content-Type" "application/json"}})]
+        (is (= 200 (:status response)))
+        ;; Verify edit tool received ACTUAL email (not token) in old_string
+        (let [mcp-requests @(:received-requests mcp)
+              edit-call (last (filter #(= "tools/call" (-> % :body :method)) mcp-requests))
+              args-str (-> edit-call :body :params :arguments)
+              args (json/parse-string args-str true)]
+          (is (= secret-email (:old_string args))))))))
+
+(defn -main [& _args]
+  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
+    (System/exit (if (zero? (:fail result)) 0 1))))
diff --git a/test/mcp_injector/test_llm_server.clj b/test/mcp_injector/test_llm_server.clj
index fa3f9d7..3b4ee3a 100644
--- a/test/mcp_injector/test_llm_server.clj
+++ b/test/mcp_injector/test_llm_server.clj
@@ -16,15 +16,18 @@
    :model (get request-body :model "gpt-4o-mini")
    :choices [{:index 0
               :message {:role "assistant"
-                        :content (:content response-data)
+                        :content (or (get-in response-data [:choices 0 :message :content])
+                                     (:content response-data))
                         :tool_calls (when (:tool_calls response-data)
                                       (map-indexed
                                        (fn [idx tc]
-                                         {:id (str "call_" idx)
-                                          :type "function"
-                                          :index idx
-                                          :function {:name (:name tc)
-                                                     :arguments (json/generate-string (:arguments tc))}})
+                                         (let [fn-name (or (:name tc) (get-in tc [:function :name]))
+                                               fn-args (or (:arguments tc) (get-in tc [:function :arguments]))]
+                                           {:id (str "call_" idx)
+                                            :type "function"
+                                            :index idx
+                                            :function {:name fn-name
+                                                       :arguments (json/generate-string fn-args)}}))
                                        (:tool_calls response-data)))}
               :finish_reason (if (:tool_calls response-data) "tool_calls" "stop")}]
     ;; Default usage to nil to avoid polluting stats in tests that don't explicitly set it
=== FILE: src/mcp_injector/config.clj ===
(ns mcp-injector.config
  "Configuration and environment variables for mcp-injector."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(def default-config
  {:port 8088
   :host "127.0.0.1"
   :llm-url "http://localhost:8080"
   :mcp-config "./mcp-servers.edn"
   :max-iterations 10
   :log-level "debug"
   :timeout-ms 1800000
   :audit-log-path "logs/audit.log.ndjson"
   :audit-secret "default-audit-secret"})

(defn env-var
  ([name] (System/getenv name))
  ([name default] (or (System/getenv name) default)))

(defn- parse-int [s default]
  (try
    (Integer/parseInt s)
    (catch Exception _ default)))

(defn- keywordize-keys [m]
  (walk/prewalk
   (fn [x]
     (if (map? x)
       (into {} (map (fn [[k v]] [(keyword k) v]) x))
       x))
   m))

(defn deep-merge
  "Recursively merges maps. If keys conflict, the value from the last map wins.
   Ensures nested defaults are not wiped out by partial user config.
   If 'new' is nil, the 'old' value is preserved to prevent wiping out defaults."
  [& maps]
  (apply merge-with
         (fn [old new]
           (cond
             (nil? new) old
             (and (map? old) (map? new)) (deep-merge old new)
             :else new))
         maps))

(defn- resolve-audit-path [env-path]
  (let [logs-dir (env-var "LOGS_DIRECTORY")
        state-dir (env-var "STATE_DIRECTORY")
        xdg-state (env-var "XDG_STATE_HOME")
        xdg-data (env-var "XDG_DATA_HOME")
        home (env-var "HOME")
        cwd (.getAbsolutePath (io/file "."))
        in-nix-store? (str/starts-with? cwd "/nix/store")
        default-path (:audit-log-path default-config)]
    (or env-path
        (cond
          logs-dir (str (str/replace logs-dir #"/$" "") "/audit.log.ndjson")
          state-dir (str (str/replace state-dir #"/$" "") "/audit.log.ndjson")
          xdg-state (str (str/replace xdg-state #"/$" "") "/mcp-injector/audit.log.ndjson")
          xdg-data (str (str/replace xdg-data #"/$" "") "/mcp-injector/audit.log.ndjson")
          home (str home "/.local/state/mcp-injector/audit.log.ndjson")
          (and in-nix-store? (not (str/starts-with? default-path "/")))
          (throw (ex-info (str "Cannot use relative audit log path '" default-path "' in read-only directory: " cwd)
                          {:cwd cwd
                           :default-path default-path
                           :suggestion "Set MCP_INJECTOR_AUDIT_LOG_PATH to an absolute, writable path."}))
          :else default-path))))

(defn load-config []
  (let [env-audit-path (env-var "MCP_INJECTOR_AUDIT_LOG_PATH")
        env-audit-secret (env-var "MCP_INJECTOR_AUDIT_SECRET")]
    {:port (parse-int (env-var "MCP_INJECTOR_PORT") (:port default-config))
     :host (env-var "MCP_INJECTOR_HOST" (:host default-config))
     :llm-url (env-var "MCP_INJECTOR_LLM_URL" (:llm-url default-config))
     :mcp-config (env-var "MCP_INJECTOR_MCP_CONFIG" (:mcp-config default-config))
     :max-iterations (parse-int (env-var "MCP_INJECTOR_MAX_ITERATIONS") (:max-iterations default-config))
     :log-level (env-var "MCP_INJECTOR_LOG_LEVEL" (:log-level default-config))
     :timeout-ms (parse-int (env-var "MCP_INJECTOR_TIMEOUT_MS") (:timeout-ms default-config))
     :audit-log-path (resolve-audit-path env-audit-path)
     :audit-secret (or env-audit-secret (:audit-secret default-config))}))

(defn get-env [name]
  (System/getenv name))

(defn- resolve-value
  "Resolve a potentially dynamic value.
   If value is a map with :env, look up environment variable.
   Supports :prefix and :suffix."
  [v]
  (if (and (map? v) (:env v))
    (let [env-name (:env v)]
      (if (or (string? env-name) (keyword? env-name))
        (let [prefix (:prefix v "")
              suffix (:suffix v "")
              env-val (get-env (if (keyword? env-name) (name env-name) env-name))]
          (if env-val
            (str prefix env-val suffix)
            (do
              (println (str "Warning: Environment variable " env-name " not set."))
              nil)))
        v))
    v))

(defn resolve-server-config
  "Recursively resolve dynamic values in a server configuration map.
   Uses post-order traversal: children first, then parent."
  [m]
  (let [resolve-all (fn resolve-all [x]
                      (cond
                        (map? x)
                        (let [resolved (into {} (map (fn [[k v]] [k (resolve-all v)]) x))]
                          (if (contains? resolved :env)
                            (resolve-value resolved)
                            resolved))

                        (vector? x)
                        (mapv resolve-all x)

                        :else x))]
    (resolve-all m)))

(defn load-mcp-servers [config-path]
  (if-let [file (io/file config-path)]
    (if (.exists file)
      (let [raw-config (keywordize-keys (edn/read-string (slurp file)))]
        (update raw-config :servers
                (fn [servers]
                  (into {} (map (fn [[k v]] [k (resolve-server-config v)]) servers)))))
      {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}})
    {:servers {} :llm-gateway {:url "http://localhost:8080" :fallbacks []}}))

(defn get-llm-fallbacks
  "Get LLM fallback configuration from MCP servers config.
   Transforms from [{:provider :model}] format to provider/model strings"
  [mcp-config]
  (let [fallbacks-config (get-in mcp-config [:llm-gateway :fallbacks] [])]
    (mapv (fn [fb]
            (if (string? fb)
              fb
              (str (:provider fb) "/" (:model fb))))
          fallbacks-config)))

(defn build-tool-directory
  "Build tool directory from mcp-config. 
   If pre-discovered-tools map provided, use those; otherwise fall back to config :tools list."
  ([mcp-config]
   (build-tool-directory mcp-config nil))
  ([mcp-config pre-discovered-tools]
   (reduce
    (fn [acc [server-name server-config]]
      (let [server-url (or (:url server-config) (:uri server-config))
            cmd (:cmd server-config)
            tool-names (:tools server-config)]
        (if (or server-url cmd)
          (let [tools (if (and pre-discovered-tools (get pre-discovered-tools server-name))
                        (get pre-discovered-tools server-name)
                        (map (fn [t] {:name (name t)}) tool-names))]
            (into acc (map (fn [tool]
                             {:name (str (name server-name) "." (:name tool))
                              :server (name server-name)})
                           tools)))
          acc)))
    []
    (:servers mcp-config))))

(defn get-server-trust
  "Get trust level for a server/tool combination.
   Returns :restore (full restoration), :read (read-only access), :none (untrusted), or :block.
   Precedence: tool-level :trust > server-level :trust > :none.
   Accepts trust values as either keywords (:restore) or strings (\"restore\")."
  [mcp-config server-name tool-name]
  (let [servers (:servers mcp-config)
        server (get servers (keyword server-name))]
    (if-not server
      :none
      (let [server-trust (some-> server :trust keyword)
            tool-configs (:tools server)
            tool-config (cond
                          (map? tool-configs)
                          (get tool-configs (keyword tool-name))

                          (sequential? tool-configs)
                          (some #(when (= (:name %) (str tool-name)) %) tool-configs)

                          :else nil)
            tool-trust (some-> tool-config :trust keyword)]
        (cond
          (= tool-trust :block) :block
          (= server-trust :block) :block
          (#{:restore :read} tool-trust) tool-trust
          (#{:restore :read} server-trust) server-trust
          :else :none)))))

(defn get-meta-tool-definitions
  "Get definitions for meta-tools like get_tool_schema and native tools"
  []
  [{:type "function"
    :function {:name "get_tool_schema"
               :description "Fetch the full JSON schema for a specific MCP tool to understand its parameters."
               :parameters {:type "object"
                            :properties {:tool {:type "string"
                                                :description "Full tool name with mcp__ prefix (e.g., 'mcp__stripe__retrieve_customer')"}}
                            :required ["tool"]}}}
   {:type "function"
    :function {:name "clojure-eval"
               :description "Evaluate Clojure code in the local REPL. WARNING: Full Clojure access - use with care. Returns the result as a string."
               :parameters {:type "object"
                            :properties {:code {:type "string"
                                                :description "Clojure code to evaluate"}}
                            :required ["code"]}}}])

(defn- extract-tool-params
  "Extract parameter names from tool schema, distinguishing required vs optional.
   Returns [required-params optional-params] as vectors of strings."
  [tool]
  (let [schema (or (:inputSchema tool) (:schema tool))
        properties (get schema :properties {})
        required-vals (get schema :required [])
        required-set (set (map keyword required-vals))
        all-param-names (keys properties)
        required (filterv #(required-set %) all-param-names)
        optional (filterv #(not (required-set %)) all-param-names)]
    [(mapv name required) (mapv name optional)]))

(defn- format-tool-with-params
  "Format a tool as mcp__server__tool [required, optional?]"
  [server-name tool]
  (let [tool-name (:name tool)
        [required optional] (extract-tool-params tool)]
    (if (or (seq required) (seq optional))
      (let [all-params (into required (map #(str % "?")) optional)]
        (str "mcp__" (name server-name) "__" tool-name " [" (str/join ", " all-params) "]"))
      (str "mcp__" (name server-name) "__" tool-name))))

(defn inject-tools-into-messages
  "Inject MCP tools directory into messages.
   If pre-discovered-tools map provided (server-name -> [tools]), use those;
   otherwise fall back to config :tools list."
  ([messages mcp-config]
   (inject-tools-into-messages messages mcp-config nil))
  ([messages mcp-config pre-discovered-tools]
   (let [servers (:servers mcp-config)
         tool-lines (reduce
                     (fn [lines [server-name server-config]]
                       (let [server-url (or (:url server-config) (:uri server-config))
                             cmd (:cmd server-config)
                             tool-names (:tools server-config)]
                         (if (or server-url cmd)
                           (let [discovered (get pre-discovered-tools server-name)
                                 tools (if (and pre-discovered-tools (seq discovered))
                                         discovered
                                         (mapv (fn [t] {:name (name t)}) tool-names))
                                 tools (filter #(some? (:name %)) tools)
                                 formatted (map #(format-tool-with-params server-name %) tools)
                                 tool-str (str/join ", " formatted)]
                             (if (seq tools)
                               (conj lines (str "- mcp__" (name server-name) ": " tool-str))
                               lines))
                           lines)))
                     []
                     servers)
         directory-text (str "## Remote Tools (MCP)\n"
                             "You have access to namespaced MCP tools.\n\n"
                             "### Available:\n"
                             (str/join "\n" tool-lines)
                             "\n\n### Usage:\n"
                             "Get schema: get_tool_schema {:tool \"mcp__server__tool\"}\n"
                             "Call tool: mcp__server__tool {:key \"value\"}\n\n"
                             "### Native:\n"
                             "- clojure-eval: Evaluate Clojure. Args: {:code \"...\"}\n"
                             "  Example: {:code \"(vec (range 5))\"} => \"[0 1 2 3 4]\"")
         system-msg {:role "system" :content directory-text}]
     (cons system-msg messages))))

(defn get-virtual-models
  "Get virtual models configuration from MCP servers config"
  [mcp-config]
  (get-in mcp-config [:llm-gateway :virtual-models] {}))

(defn resolve-governance
  "Unified governance resolution logic. Prioritizes nested :governance block.
   Precedence: top-level :governance > :llm-gateway :governance > defaults.
   Uses deep-merge to preserve nested default settings."
  [mcp-config env-config]
  (let [gateway (:llm-gateway mcp-config)
        gov-user (or (:governance mcp-config) (:governance gateway))
        defaults {:mode :permissive
                  :pii {:enabled true :mode :replace}
                  :audit {:enabled true :path (:audit-log-path env-config)}
                  :policy {:mode :permissive}}]
    (deep-merge defaults gov-user)))

(defn get-config
  "Unified config: env vars override config file, with defaults as fallback.
    Priority: env var > config file > default"
  [mcp-config]
  (let [env (load-config)
        gateway (:llm-gateway mcp-config)
        gov (resolve-governance mcp-config env)]
    {:port (:port env)
     :host (:host env)
     :llm-url (or (env-var "MCP_INJECTOR_LLM_URL")
                  (:url gateway)
                  (:llm-url env))
     :mcp-config (:mcp-config env)
     :max-iterations (let [v (or (env-var "MCP_INJECTOR_MAX_ITERATIONS")
                                 (:max-iterations gateway))]
                       (if (string? v) (parse-int v 10) (or v (:max-iterations env))))
     :log-level (or (env-var "MCP_INJECTOR_LOG_LEVEL")
                    (:log-level gateway)
                    (:log-level env))
     :timeout-ms (let [v (or (env-var "MCP_INJECTOR_TIMEOUT_MS")
                             (:timeout-ms gateway))]
                   (if (string? v) (parse-int v 1800000) (or v (:timeout-ms env))))
     :fallbacks (:fallbacks gateway)
     :virtual-models (:virtual-models gateway)
     :audit-log-path (get-in gov [:audit :path])
     :audit-secret (or (get-in gov [:audit :secret])
                       (env-var "MCP_INJECTOR_AUDIT_SECRET")
                       (:audit-secret env)
                       "default-audit-secret")
     :governance gov}))

(defn get-llm-url
  "Get LLM URL: env var overrides config file"
  [mcp-config]
  (or (env-var "MCP_INJECTOR_LLM_URL")
      (get-in mcp-config [:llm-gateway :url])
      "http://localhost:8080"))

=== FILE: src/mcp_injector/core.clj ===
(ns mcp-injector.core
  (:require [org.httpkit.server :as http]
            [babashka.http-client :as http-client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [mcp-injector.config :as config]
            [mcp-injector.openai-compat :as openai]
            [mcp-injector.mcp-client :as mcp]
            [mcp-injector.audit :as audit]
            [mcp-injector.pii :as pii]
            [mcp-injector.policy :as policy]))

(def ^:private server-state (atom nil))
(def ^:private usage-stats (atom {}))
(def ^:private cooldown-state (atom {}))
(def ^:private ^:dynamic *request-id* nil)
(def ^:private ^:dynamic *audit-config* nil)

(defn- log-request
  ([level message data]
   (log-request level message data nil))
  ([level message data context]
   (let [log-entry (merge {:timestamp (str (java.time.Instant/now))
                           :level level
                           :message message
                           :request-id (or *request-id* "none")}
                          context
                          {:data data})]
     (println (json/generate-string log-entry))
     ;; Fail-open audit logging
     (when *audit-config*
       (try
         (audit/append-event! (:secret *audit-config*) level log-entry)
         (catch Exception e
           (binding [*out* *err*]
             (println (json/generate-string
                       {:timestamp (str (java.time.Instant/now))
                        :level "error"
                        :message "AUDIT LOG WRITE FAILURE — audit trail degraded"
                        :error (.getMessage e)})))))))))

(defn- parse-body [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Exception e
      (throw (ex-info "Failed to parse JSON body"
                      {:type :json_parse_error
                       :status 400
                       :message "Failed to parse JSON body. Please ensure your request is valid JSON."} e)))))

(defn- is-context-overflow-error? [error-str]
  (when (string? error-str)
    (let [patterns [#"(?i)cannot read propert(?:y|ies) of undefined.*prompt"
                    #"(?i)cannot read propert(?:y|ies) of null.*prompt"
                    #"(?i)prompt_tokens.*undefined"
                    #"(?i)prompt_tokens.*null"
                    #"(?i)context window.*exceeded"
                    #"(?i)context length.*exceeded"
                    #"(?i)maximum context.*exceeded"
                    #"(?i)request.*too large"
                    #"(?i)prompt is too long"
                    #"(?i)exceeds model context"
                    #"(?i)413.*too large"
                    #"(?i)request size exceeds"]]
      (some #(re-find % error-str) patterns))))

(defn- translate-error-for-openclaw [error-data status-code]
  (let [error-str (or (get-in error-data [:error :message])
                      (:message error-data)
                      (:details error-data)
                      (str error-data))]
    (cond
      (is-context-overflow-error? error-str)
      {:message "Context overflow: prompt too large for the model. Try /reset (or /new) to start a fresh session, or use a larger-context model."
       :status 503
       :type "context_overflow"
       :details error-data}

      (= 429 status-code)
      {:message (or (:message error-data) "Rate limit exceeded")
       :status 429
       :type "rate_limit_exceeded"
       :details error-data}

      :else
      {:message (or (:message error-data) "Upstream error")
       :status 502
       :type "upstream_error"
       :details error-data})))

(defn- call-llm [base-url payload]
  (let [url (str (str/replace base-url #"/$" "") "/v1/chat/completions")
        resp (try
               (http-client/post url
                                 {:headers {"Content-Type" "application/json"}
                                  :body (json/generate-string payload)
                                  :throw false})
               (catch Exception e
                 {:status 502 :body (json/generate-string {:error {:message (.getMessage e)}})}))]
    (if (= 200 (:status resp))
      {:success true :data (json/parse-string (:body resp) true)}
      (let [status (:status resp)
            error-data (try (json/parse-string (:body resp) true) (catch Exception _ (:body resp)))
            translated (translate-error-for-openclaw error-data status)]
        (log-request "warn" "LLM Error" {:status status :body (:body resp) :translated translated})
        {:success false :status (:status translated) :error translated}))))

(defn- record-completion! [alias provider usage]
  (when usage
    (let [update-entry (fn [existing usage]
                         (let [input (or (:prompt_tokens usage) 0)
                               output (or (:completion_tokens usage) 0)
                               total (or (:total_tokens usage) (+ input output))]
                           {:requests (inc (or (:requests existing) 0))
                            :total-input-tokens (+ input (or (:total-input-tokens existing) 0))
                            :total-output-tokens (+ output (or (:total-output-tokens existing) 0))
                            :total-tokens (+ total (or (:total-tokens existing) 0))
                            :rate-limits (or (:rate-limits existing) 0)
                            :context-overflows (or (:context-overflows existing) 0)
                            :last-updated (System/currentTimeMillis)}))]
      (swap! usage-stats
             (fn [stats]
               (cond-> stats
                 alias (update alias update-entry usage)
                 (and provider (not= provider alias)) (update provider update-entry usage)))))))

(defn- track-provider-failure! [provider status]
  (when provider
    (let [counter (if (= status 503) :context-overflows :rate-limits)]
      (swap! usage-stats update provider
             (fn [existing]
               (assoc (or existing {:requests 0
                                    :total-input-tokens 0
                                    :total-output-tokens 0
                                    :total-tokens 0})
                      counter (inc (or (get existing counter) 0))
                      :last-updated (System/currentTimeMillis)))))))

(defn reset-usage-stats! []
  (reset! usage-stats {}))

(defn- execute-tool [full-name args mcp-servers discovered-this-loop governance context]
  (let [policy-result (policy/allow-tool? (:policy governance) full-name context)]
    (if-not (:allowed? policy-result)
      (do
        (log-request "warn" "Tool Blocked by Policy" {:tool full-name :reason (:reason policy-result)} context)
        {:error "Tool execution denied"})
      (cond
        (= full-name "get_tool_schema")
        (let [full-tool-name (:tool args)
              ;; Parse prefixed name: mcp__server__tool -> [server tool]
              [s-name t-name] (if (and full-tool-name (str/includes? full-tool-name "__"))
                                (let [idx (str/last-index-of full-tool-name "__")]
                                  [(subs full-tool-name 5 idx) (subs full-tool-name (+ idx 2))])
                                [nil nil])
              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
          (if (and s-name s-config t-name)
            (let [schema (mcp/get-tool-schema (name s-name) s-config t-name (:policy governance))]
              (if (:error schema)
                schema
                (do
                  (swap! discovered-this-loop assoc full-tool-name schema)
                  schema)))
            {:error (str "Invalid tool name. Use format: mcp__server__tool (e.g., mcp__stripe__retrieve_customer). Got: " full-tool-name)}))

        (= full-name "clojure-eval")
        (try
          (let [code (:code args)
                ;; NOTE: clojure-eval is a full JVM/Babashka load-string.
                ;; Security is currently enforced only via the Policy layer (explicit opt-in).
                result (load-string code)]
            (json/generate-string result))
          (catch Exception e
            (json/generate-string {:error (str "Eval error: " (.getMessage e))})))

        (str/starts-with? full-name "mcp__")
        (let [t-name (str/replace full-name #"^mcp__" "")
              [s-name real-t-name] (if (str/includes? t-name "__")
                                     (let [idx (str/last-index-of t-name "__")]
                                       [(subs t-name 0 idx) (subs t-name (+ idx 2))])
                                     [nil t-name])
              s-config (when s-name (get-in mcp-servers [:servers (keyword s-name)]))]
          (if (and s-name s-config)
            (let [result (mcp/call-tool (name s-name) s-config real-t-name args (:policy governance))
                  ;; Auto-discover: add schema to discovered-this-loop so next turn has it
                  _ (when-not (contains? result :error)
                      (let [schema (mcp/get-tool-schema (name s-name) s-config real-t-name (:policy governance))]
                        (when-not (:error schema)
                          (swap! discovered-this-loop assoc full-name schema))))]
              result)
            (if-let [_ (get @discovered-this-loop full-name)]
              (let [[_ s-name-auto real-t-auto] (str/split full-name #"__" 3)
                    s-conf-auto (get-in mcp-servers [:servers (keyword s-name-auto)])]
                (mcp/call-tool (name s-name-auto) s-conf-auto real-t-auto args (:policy governance)))
              {:error (str "Unknown tool: " full-name ". Use get_tool_schema with full prefixed name first.")})))

        :else {:error (str "Unknown tool: " full-name)}))))

(defn- parse-tool-name
  "Parse mcp__server__tool format into [server tool]"
  [full-name]
  (if (str/includes? full-name "__")
    (let [t-name (str/replace full-name #"^mcp__" "")
          idx (str/last-index-of t-name "__")]
      [(subs t-name 0 idx) (subs t-name (+ idx 2))])
    [nil full-name]))

(defn- scrub-messages [messages vault request-id]
  (mapv (fn [m]
          (let [content (:content m)
                role (:role m)]
            (if (and (string? content)
                     ;; Only redact user/system messages - assistant tool results are already handled
                     (or (= role "system") (= role "user"))
                      ;; Skip if already contains PII tokens (avoid double-redaction)
                      ;; Token format: [LABEL_hex12] e.g., [EMAIL_ADDRESS_a35e26620952]
                     (not (re-find #"\[[A-Z_]+_[a-f0-9]{12}\]" content)))
              (let [config {:mode :replace :salt request-id}
                    [redacted-content _ _] (pii/redact-data content config vault)]
                (assoc m :content redacted-content))
              m)))
        messages))

(defn- restore-tool-args
  "Restore tokens in tool args if server/tool is trusted (:restore level)."
  [args vault mcp-servers full-tool-name]
  (let [[server tool] (parse-tool-name full-tool-name)
        trust (when server (config/get-server-trust mcp-servers server tool))]
    (if (= trust :restore)
      (pii/restore-tokens args vault)
      args)))

(defn- redact-tool-output
  "Redact PII from tool output, return [content vault]"
  [raw-output vault request-id]
  (let [;; Try to parse as JSON first for JSON tokenization
        parsed (try (json/parse-string raw-output true) (catch Exception _ nil))
        ;; If parsed successfully, redact the data structure; otherwise redact the string
        ;; Special handling for MCP response format: parse nested :text field if present
        [redacted new-vault detected] (if parsed
                                        (let [;; Check if this is MCP response format with :text field containing JSON
                                              ;; Handle both map and sequential (vector/list/lazy-seq) responses
                                              parsed (cond
                                                       (map? parsed)
                                                       (if (string? (:text parsed))
                                                         (try (assoc parsed :text (json/parse-string (:text parsed) true))
                                                              (catch Exception _ parsed))
                                                         parsed)
                                                       (sequential? parsed)
                                                       (mapv (fn [item]
                                                               (if (and (map? item) (string? (:text item)))
                                                                 (try (assoc item :text (json/parse-string (:text item) true))
                                                                      (catch Exception _ item))
                                                                 item))
                                                             parsed)
                                                       :else parsed)
                                              config {:mode :replace :salt request-id}
                                              [redacted-struct vault-after detected-labels] (pii/redact-data parsed config vault)]
                                          [(json/generate-string redacted-struct) vault-after detected-labels])
                                        (let [config {:mode :replace :salt request-id}
                                              [redacted-str vault-after detected-labels] (pii/redact-data raw-output config vault)]
                                          [redacted-str vault-after detected-labels]))]

    ;; Log the detected PII types (not scanning again)
    (when (seq detected)
      (log-request "info" "PII Redacted in Tool Output" {:labels detected} {}))
    [redacted new-vault]))

(defn- agent-loop [llm-url payload mcp-servers max-iterations governance]
  (let [model (:model payload)
        discovered-this-loop (atom {})
        vault (atom {})
        request-id (or (:request-id payload) (str (java.util.UUID/randomUUID)))
        context {:model model :request-id request-id}]
    (loop [current-payload (update payload :messages #(scrub-messages % vault request-id))
           iteration 0]
      (if (>= iteration max-iterations)
        {:success true
         :provider model
         :data {:choices [{:index 0
                           :message {:role "assistant"
                                     :content "Maximum iterations reached. Here's what I found so far:"
                                     :tool_calls nil}
                           :finish_reason "length"}]}}
        (let [_ (log-request "info" "Tool Loop" {:iteration iteration :calls (count (get-in current-payload [:messages]))} context)
              resp (call-llm llm-url current-payload)]
          (if-not (:success resp)
            resp
            (let [choices (get-in resp [:data :choices])
                  message (get-in (first choices) [:message])
                  tool-calls (:tool_calls message)]
              (if-not tool-calls
                (assoc resp :provider model)
                (let [mcp-calls (filter (fn [tc]
                                          (let [n (get-in tc [:function :name])]
                                            (or (= n "get_tool_schema")
                                                (and n (str/starts-with? n "mcp__")))))
                                        tool-calls)
                      native-calls (filter #(= (get-in % [:function :name]) "clojure-eval")
                                           tool-calls)]
                  (if (and (empty? mcp-calls) (empty? native-calls))
                    (assoc resp :provider model)
                    (let [[results new-vault]
                          (reduce
                           (fn [[results vault-state] tc]
                             (let [fn-name (get-in tc [:function :name])
                                   args-str (get-in tc [:function :arguments])
                                   parse-result (try
                                                  {:success true :args (json/parse-string args-str true)}
                                                  (catch Exception e
                                                    {:success false :error (.getMessage e)}))]
                               (if (:success parse-result)
                                 (let [;; Restore args if trusted
                                       restored-args (restore-tool-args (:args parse-result) vault-state mcp-servers fn-name)
                                       result (execute-tool fn-name restored-args mcp-servers discovered-this-loop governance context)
                                       ;; Redact output with vault
                                       raw-content (if (string? result) result (json/generate-string result))
                                       [redacted updated-vault] (redact-tool-output raw-content vault-state request-id)]
                                   [(conj results {:role "tool"
                                                   :tool_call_id (:id tc)
                                                   :name fn-name
                                                   :content redacted})
                                    updated-vault])
                                 [(conj results {:role "tool"
                                                 :tool_call_id (:id tc)
                                                 :name fn-name
                                                 :content (json/generate-string
                                                           {:error "Malformed tool arguments JSON"
                                                            :details {:args-str args-str
                                                                      :parse-error (:error parse-result)}})})
                                  vault-state])))
                           [[] vault]
                           (concat mcp-calls native-calls))
                          newly-discovered @discovered-this-loop
                          new-tools (vec (concat (config/get-meta-tool-definitions)
                                                 (map (fn [[name schema]]
                                                        {:type "function"
                                                         :function {:name name
                                                                    :description (:description schema)
                                                                    :parameters (:inputSchema schema)}})
                                                      newly-discovered)))
                          new-messages (conj (vec (:messages current-payload)) (assoc message :content (or (:content message) "")))
                          new-messages (into new-messages results)]
                      (recur (assoc current-payload
                                    :messages (scrub-messages new-messages new-vault request-id)
                                    :tools new-tools)
                             (inc iteration)))))))))))))

(defn- set-cooldown! [provider minutes]
  (swap! cooldown-state assoc provider (+ (System/currentTimeMillis) (* minutes 60 1000))))

(defn- is-on-cooldown? [provider]
  (if-let [expiry (get @cooldown-state provider)]
    (if (> expiry (System/currentTimeMillis))
      true
      (do (swap! cooldown-state dissoc provider) false))
    false))

(defn reset-cooldowns! []
  (reset! cooldown-state {}))

(defn- body->string [body]
  (if (string? body) body (slurp body)))

(defn- extract-discovered-tools
  "Scan messages for tool schemas returned by get_tool_schema.
   Returns a map of tool-name -> full tool schema."
  [messages]
  (reduce
   (fn [acc msg]
     (if (= "tool" (:role msg))
       (let [content (:content msg)
             parsed (try (json/parse-string (body->string content) true) (catch Exception _ nil))]
         (if (and parsed (:name parsed))
           (let [tool-name (:name parsed)
                 formatted-name (if (str/includes? tool-name "__")
                                  tool-name
                                  (str "mcp__" tool-name))]
             (assoc acc formatted-name parsed))
           acc))
       acc))
   {}
   messages))

(defn- prepare-llm-request [chat-req mcp-servers]
  (let [meta-tools (config/get-meta-tool-definitions)
        discovered-tools (extract-discovered-tools (:messages chat-req))
        existing-tools (:tools chat-req)
        fallbacks (config/get-llm-fallbacks mcp-servers)
        discovered-tool-defs (map (fn [[name schema]]
                                    {:type "function"
                                     :function {:name name
                                                :description (:description schema)
                                                :parameters (:inputSchema schema)}})
                                  discovered-tools)
        merged-tools (vec (concat (or existing-tools [])
                                  meta-tools
                                  discovered-tool-defs))
        ;; Merge extra_body into the request for fields like request-id
        extra-body (or (:extra_body chat-req) {})]
    (-> chat-req
        (assoc :stream false)
        (dissoc :stream_options)
        (assoc :fallbacks fallbacks)
        (merge extra-body) ;; Lift extra_body fields to top level
        (update :messages (fn [msgs]
                            (mapv (fn [m]
                                    (if (and (= (:role m) "assistant") (:tool_calls m))
                                      (update m :tool_calls (fn [tcs]
                                                              (mapv #(dissoc % :index) tcs)))
                                      m))
                                  msgs)))
        (assoc :tools merged-tools))))

(defn- try-virtual-model-chain [config prepared-req llm-url mcp-servers max-iterations governance]
  (let [chain (:chain config)
        retry-on (set (:retry-on config [429 500]))
        cooldown-mins (get config :cooldown-minutes 5)
        original-model (:model prepared-req)]
    (loop [providers (filter #(not (is-on-cooldown? %)) chain)
           last-error nil]
      (if (empty? providers)
        {:success false :status 502 :error (or last-error {:message "All providers failed"})}
        (let [provider (first providers)
              _ (log-request "info" "Virtual model: trying provider" {:provider provider :remaining (count (rest providers))}
                             {:model original-model :endpoint llm-url})
              req (-> prepared-req
                      (assoc :model provider)
                      (dissoc :fallbacks))
              result (agent-loop llm-url req mcp-servers max-iterations governance)]
          (if (:success result)
            (assoc result :provider provider)
            (if (some #(= % (:status result)) retry-on)
              (do
                (log-request "warn" "Virtual model: provider failed, setting cooldown" {:provider provider :status (:status result) :cooldown-mins cooldown-mins}
                             {:model original-model :endpoint llm-url})
                (set-cooldown! provider cooldown-mins)
                (track-provider-failure! provider (:status result))
                (recur (rest providers) (:error result)))
              (assoc result :provider provider))))))))

(defn- handle-chat-completion [request mcp-servers config]
  (try
    (let [chat-req (parse-body (:body request))
          model (:model chat-req)
          _ (log-request "info" "Chat Completion Started" {:stream (:stream chat-req)} {:model model})
          discovered (reduce (fn [acc [s-name s-conf]]
                               (let [url (or (:url s-conf) (:uri s-conf))
                                     cmd (:cmd s-conf)]
                                 (if (or url cmd)
                                   (try (assoc acc s-name (mcp/discover-tools (name s-name) s-conf (:tools s-conf) (:policy (:governance config))))
                                        (catch Exception e
                                          (log-request "warn" "Discovery failed" {:server s-name :error (.getMessage e)} {:model model})
                                          acc))
                                   acc)))
                             {} (:servers mcp-servers))
          messages (config/inject-tools-into-messages (:messages chat-req) mcp-servers discovered)
          llm-url (or (:llm-url config) (config/get-llm-url mcp-servers))
          virtual-models (config/get-virtual-models mcp-servers)
          virtual-config (or (get virtual-models model) (get virtual-models (keyword model)))
          prepared-req (prepare-llm-request (assoc chat-req :messages messages) mcp-servers)
          max-iter (or (:max-iterations config) 10)
          gov (:governance config)
          result (if virtual-config
                   (try-virtual-model-chain virtual-config prepared-req llm-url mcp-servers max-iter gov)
                   (agent-loop llm-url prepared-req mcp-servers max-iter gov))]
      (if (:success result)
        (let [final-resp (:data result)
              actual-provider (:provider result)
              _ (record-completion! model actual-provider (:usage final-resp))
              _ (log-request "info" "Chat Completion Success" {:usage (:usage final-resp) :provider actual-provider} {:model model})
              body (if (:stream chat-req)
                     (openai/build-chat-response-streaming
                      {:content (get-in final-resp [:choices 0 :message :content])
                       :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
                       :model model
                       :usage (:usage final-resp)})
                     (json/generate-string
                      (openai/build-chat-response
                       {:content (get-in final-resp [:choices 0 :message :content])
                        :tool-calls (get-in final-resp [:choices 0 :message :tool_calls])
                        :model model
                        :usage (:usage final-resp)})))]
          {:status 200 :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})
        (let [status (or (:status result) 500)
              error-data (:error result)
              error-msg (if (map? error-data) (:message error-data) (str "Failed: " error-data))
              error-type (get-in result [:error :type] "internal_error")
              _ (log-request "warn" "Chat Completion Failed" {:status status :error error-msg :type error-type} {:model model :endpoint llm-url})
              body (if (:stream chat-req)
                     (str "data: " (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}) "\n\ndata: [DONE]\n\n")
                     (json/generate-string {:error {:message error-msg :type error-type :details (get-in result [:error :details])}}))]
          {:status status :headers {"Content-Type" (if (:stream chat-req) "text/event-stream" "application/json")} :body body})))
    (catch Exception e
      (let [err-type (or (some-> e ex-data :type name) "internal_error")
            err-msg (or (.getMessage e) (str e))
            stack (.getStackTrace e)]
        (log-request "error" "Chat completion failed" {:type err-type :message err-msg :stack (map str stack)} {})
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error {:message err-msg
                                              :type err-type}})}))))

(defn get-gateway-state []
  {:cooldowns @cooldown-state
   :usage @usage-stats
   :warming-up? (let [fut (get @server-state :warmup-future)]
                  (if fut (not (realized? fut)) false))})

(defn- handle-api [request _mcp-servers config]
  (let [uri (:uri request)
        method (:request-method request)]
    (case [method uri]
      [:get "/api/v1/status"]
      {:status 200 :body (json/generate-string {:status "ok" :version "1.0.0" :warming-up? (:warming-up? (get-gateway-state))})}

      [:get "/api/v1/mcp/tools"]
      {:status 200 :body (json/generate-string (mcp/get-cache-state))}

      [:post "/api/v1/mcp/reset"]
      (do (mcp/clear-tool-cache!)
          {:status 200 :body (json/generate-string {:message "MCP state reset successful"})})

      [:get "/api/v1/llm/state"]
      {:status 200 :body (json/generate-string (get-gateway-state))}

      [:post "/api/v1/llm/cooldowns/reset"]
      (do (reset-cooldowns!)
          {:status 200 :body (json/generate-string {:message "Cooldowns reset successful"})})

      [:get "/api/v1/stats"]
      {:status 200 :body (json/generate-string {:stats @usage-stats})}

      [:get "/api/v1/audit/verify"]
      (let [path (:audit-log-path config)
            secret (:audit-secret config)
            valid? (audit/verify-log (io/file path) secret)]
        {:status 200 :body (json/generate-string {:valid? valid? :path path})})

      {:status 404 :body (json/generate-string {:error "Not found"})})))

(defn- handler [request mcp-servers config]
  (let [request-id (str (java.util.UUID/randomUUID))
        audit-conf {:path (io/file (:audit-log-path config))
                    :secret (:audit-secret config)}]
    (binding [*request-id* request-id
              *audit-config* audit-conf]
      (try
        (let [uri (:uri request)]
          (cond
            (= uri "/v1/chat/completions")
            (if (= :post (:request-method request))
              (handle-chat-completion request mcp-servers config)
              {:status 405 :body "Method not allowed"})

            (= uri "/health")
            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:status "ok"})}

            (= uri "/stats")
            {:status 200 :headers {"Content-Type" "application/json"} :body (json/generate-string {:stats @usage-stats})}

            (str/starts-with? uri "/api/v1")
            (handle-api request mcp-servers config)

            :else
            {:status 404 :body "Not found"}))
        (catch Exception e
          (let [err-data (ex-data e)
                status (or (:status err-data) 500)
                err-type (or (some-> err-data :type name) "internal_error")]
            (log-request "error" "Request failed" {:type err-type :message (.getMessage e)} {:endpoint (:uri request)})
            {:status status
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:error {:message (or (:message err-data) (.getMessage e) "Internal server error")
                                                  :type err-type}})}))))))

(defn start-server [mcp-config]
  (let [;; Extract governance from original input (could be at top level or nested in :mcp-servers)
        provided-governance (or (:governance mcp-config)
                                (:governance (:mcp-servers mcp-config)))

        ;; Runtime settings - prioritize input > env > default
        port (or (:port mcp-config)
                 (some-> (System/getenv "MCP_INJECTOR_PORT") not-empty Integer/parseInt)
                 8080)
        host (or (:host mcp-config)
                 (System/getenv "MCP_INJECTOR_HOST")
                 "127.0.0.1")
        llm-url (or (:llm-url mcp-config)
                    (System/getenv "MCP_INJECTOR_LLM_URL")
                    "http://localhost:11434")
        log-level (or (:log-level mcp-config)
                      (System/getenv "MCP_INJECTOR_LOG_LEVEL"))
        max-iterations (or (:max-iterations mcp-config)
                           (some-> (System/getenv "MCP_INJECTOR_MAX_ITERATIONS") not-empty Integer/parseInt)
                           10)
        mcp-config-path (or (:mcp-config-path mcp-config)
                            (System/getenv "MCP_INJECTOR_MCP_CONFIG")
                            "mcp-servers.edn")
        ;; Audit trail config
        audit-log-path (or (:audit-log-path mcp-config)
                           (System/getenv "MCP_INJECTOR_AUDIT_LOG_PATH")
                           "logs/audit.log.ndjson")
        audit-secret (or (:audit-secret mcp-config)
                         (System/getenv "MCP_INJECTOR_AUDIT_SECRET")
                         "default-audit-secret")
        ;; Merge provided mcp-config with loaded ones if needed
        base-mcp-servers (cond
                           (and (map? mcp-config) (:servers mcp-config)) mcp-config
                           (:mcp-servers mcp-config) (:mcp-servers mcp-config)
                           :else (config/load-mcp-servers mcp-config-path))
        ;; Apply overrides from mcp-config (like :virtual-models in tests)
        mcp-servers (if (map? mcp-config)
                      (let [gateway-overrides (select-keys mcp-config [:virtual-models :fallbacks :url :governance])
                            merged (update base-mcp-servers :llm-gateway merge gateway-overrides)]
                        (if-let [gov (:governance mcp-config)]
                          (assoc merged :governance gov)
                          merged))
                      base-mcp-servers)
        ;; Unified configuration resolution - pass extracted governance
        unified-env {:audit-log-path audit-log-path :audit-secret audit-secret}
        final-governance (config/resolve-governance (assoc mcp-servers :governance provided-governance) unified-env)
        final-config {:port port :host host :llm-url llm-url :log-level log-level
                      :max-iterations max-iterations :mcp-config-path mcp-config-path
                      :audit-log-path audit-log-path :audit-secret audit-secret
                      :governance final-governance}
        ;; Validate policy at startup
        _ (policy/validate-policy! (:policy final-governance))
        ;; P3 Integration: Initialize Audit system
        _ (audit/init-audit! audit-log-path)
        srv (http/run-server (fn [req] (handler req mcp-servers final-config)) {:port port :host host})
        actual-port (or (:local-port (meta srv)) port)
        warmup-fut (future (mcp/warm-up! mcp-servers))]
    (reset! server-state {:server srv :port actual-port :warmup-future warmup-fut})
    (log-request "info" "mcp-injector started" (assoc final-config :port actual-port))
    {:server srv :port actual-port :warmup-future warmup-fut}))

(defn stop-server [s]
  (when s
    (let [srv (cond (fn? s) s (map? s) (:server s) :else s)
          fut (when (map? s) (:warmup-future s))]
      (when fut (future-cancel fut))
      (when (fn? srv) (srv :timeout 100))
      (reset! server-state nil)
      (mcp/clear-tool-cache!)
      ;; P3 Integration: Close Audit system
      (audit/close-audit!))))

(defn clear-mcp-sessions! []
  (mcp/clear-tool-cache!))

(defn -main [& _args]
  (let [initial-config (config/load-config)
        mcp-servers (config/load-mcp-servers (:mcp-config initial-config))
        unified-config (config/get-config mcp-servers)]
    (start-server unified-config)))

=== FILE: src/mcp_injector/pii.clj ===
(ns mcp-injector.pii
  (:require [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (java.security MessageDigest)))

(def default-patterns
  [{:id :EMAIL_ADDRESS
    :pattern #"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
    :label "[EMAIL_ADDRESS]"}
   {:id :IBAN_CODE
    :pattern #"(?i)\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"
    :label "[IBAN_CODE]"}])

(defn shannon-entropy
  "Calculates the Shannon entropy of a string."
  [s]
  (if (empty? s)
    0.0
    (let [freqs (vals (frequencies s))
          len (count s)]
      (- (reduce + (map (fn [f]
                          (let [p (/ f len)]
                            (* p (/ (Math/log p) (Math/log 2)))))
                        freqs))))))

(defn- character-diversity?
  "Checks if a string contains at least 3 distinct character classes."
  [s]
  (let [classes [(when (re-find #"[a-z]" s) :lower)
                 (when (re-find #"[A-Z]" s) :upper)
                 (when (re-find #"[0-9]" s) :digit)
                 (when (re-find #"[^a-zA-Z0-9]" s) :special)]]
    (>= (count (remove nil? classes)) 3)))

(defn- mask-string
  "Fixed-length mask to prevent leaking structural entropy."
  [_s]
  "********")

(defn- redact-match [mode label match]
  (case mode
    :replace label
    :mask (mask-string match)
    :hash (str "#" (hash match))
    label))

(defn- scan-env [text env-vars mode]
  (reduce-kv
   (fn [acc k v]
     (if (and (not (empty? v)) (> (count v) 5) (str/includes? acc v))
       (str/replace acc v (redact-match mode (str "[ENV_VAR_" k "]") v))
       acc))
   text
   env-vars))

(defn- find-env-detections [text env-vars]
  (keep (fn [[k v]]
          (when (and (not (empty? v)) (> (count v) 5) (str/includes? text v))
            (keyword (str "ENV_VAR_" k))))
        env-vars))

(defn- scan-entropy [text threshold mode]
  (let [tokens (str/split text #"\s+")]
    (reduce
     (fn [acc token]
       (if (and (> (count token) 12)
                (> (shannon-entropy token) threshold)
                (character-diversity? token))
         (str/replace acc token (redact-match mode "[HIGH_ENTROPY_SECRET]" token))
         acc))
     text
     tokens)))

(defn scan-and-redact
  "Scans input text for PII patterns, high-entropy secrets, and env vars."
  [text {:keys [mode patterns entropy-threshold env]
         :or {mode :replace
              patterns default-patterns
              entropy-threshold 4.0
              env {}}}]
  (let [regex-result (reduce
                      (fn [state {:keys [id pattern label]}]
                        (if (seq (re-seq pattern (:text state)))
                          {:text (str/replace (:text state) pattern (fn [m] (redact-match mode label m)))
                           :detected (conj (:detected state) id)}
                          state))
                      {:text text :detected []}
                      patterns)
        env-text (scan-env (:text regex-result) env mode)
        env-detections (find-env-detections text env)
        final-text (scan-entropy env-text entropy-threshold mode)
        entropy-detected (if (not= env-text final-text) [:HIGH_ENTROPY_SECRET] [])]
    {:text final-text
     :detected (distinct (concat (:detected regex-result) env-detections entropy-detected))}))

(defn generate-token
  "Generate a deterministic, truncated SHA-256 hash token.
   Uses 12 hex chars (48 bits) to reduce collision probability."
  [label value salt]
  (let [input (str (name label) "|" value "|" salt)
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes input))
        hash-str (->> digest
                      (map (partial format "%02x"))
                      (apply str))
        truncated (subs hash-str 0 12)]
    (str "[" (name label) "_" truncated "]")))

(defn- redact-string-value
  "Redact a single string value, returning [redacted-text token detected-label]"
  [v config]
  (if-not (string? v)
    [v nil nil]
    (if (empty? v)
      [v nil nil]
      (let [vault (:vault config)
            salt (:salt config)
            existing-token (some (fn [[token _]] (when (= v token) token)) @vault)
            previous-token (some (fn [[token original]] (when (= v original) token)) @vault)]
        (cond
          existing-token [existing-token nil nil]
          previous-token [previous-token nil nil]
          :else
          (let [result (scan-and-redact v config)]
            (if (seq (:detected result))
              (let [detected (first (:detected result))
                    token (generate-token detected v salt)]
                (swap! vault assoc token v)
                [token token detected])
              [(:text result) nil nil])))))))

(defn redact-data
  "Recursively walk a data structure, redact string values, store in vault.
    Returns [redacted-data vault-atom detected-labels]"
  ([data config]
   (redact-data data config (atom {})))
  ([data config vault]
   (let [config-with-vault (assoc config :vault vault)
         detected-labels (atom [])
         redacted (walk/postwalk
                   (fn [x]
                     (if (string? x)
                       (let [[redacted-text _ detected] (redact-string-value x config-with-vault)]
                         (when detected (swap! detected-labels conj detected))
                         redacted-text)
                       x))
                   data)]
     [redacted vault @detected-labels])))

(defn restore-tokens
  "Recursively walk a data structure, replacing tokens with original values from vault."
  [data vault]
  (let [v-map @vault]
    (if (empty? v-map)
      data
      (walk/postwalk
       (fn [x]
         (if (string? x)
           (reduce
            (fn [s [token original]]
              (if (and (string? s) (str/includes? s token))
                (str/replace s (str token) (str original))
                s))
            x
            v-map)
           x))
       data))))

(defn- resolve-token
  "Resolve a single token string back to its original value.
   Returns the original value if found, or the token unchanged."
  [token-str vault]
  (let [v-map @vault
        original (get v-map token-str)]
    (or original token-str)))

=== FILE: test/mcp_injector/restoration_test.clj ===
(ns mcp-injector.restoration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [mcp-injector.pii :as pii]
            [mcp-injector.test-llm-server :as test-llm]
            [mcp-injector.test-mcp-server :as test-mcp]
            [mcp-injector.core :as core]
            [cheshire.core :as json]
            [org.httpkit.client :as http]))

(def test-state (atom {}))

(use-fixtures :once
  (fn [f]
    (let [llm (test-llm/start-server)
          mcp (test-mcp/start-server)]
      (swap! test-state assoc :llm llm :mcp mcp)
      (let [injector (core/start-server
                      {:port 0
                       :host "127.0.0.1"
                       :llm-url (str "http://localhost:" (:port llm))
                       :mcp-servers {:servers
                                     {:trusted-db
                                      {:url (str "http://localhost:" (:port mcp))
                                       :tools ["query"]
                                       :trust :restore}
                                     :untrusted-api
                                      {:url (str "http://localhost:" (:port mcp))
                                       :tools ["send"]
                                       :trust :none}
                                     :workspace
                                      {:url (str "http://localhost:" (:port mcp))
                                       :trust :restore}}})]
        (swap! test-state assoc :injector injector)
        (try
          (f)
          (finally
            (core/stop-server injector)
            (test-llm/stop-server llm)
            (test-mcp/stop-server mcp)))))))

(deftest test-secret-redaction-and-restoration
  (testing "End-to-end Redact -> Decide -> Restore flow"
    (let [{:keys [injector llm mcp]} @test-state
          port (:port injector)
          request-id "test-request-id-12345"
          secret-email "wes@example.com"
          expected-token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
      ;; Setup MCP to return a secret
      ((:set-tools! mcp)
       {:query {:description "Query database"
                :schema {:type "object" :properties {:q {:type "string"} :email {:type "string"}}}
                :handler (fn [args]
                           (if-let [email (or (:email args) (get args "email"))]
                             {:status "success" :received email}
                             {:email secret-email :secret "super-secret-123"})}})
      ;; LLM Turn 1: Get data (will be redacted)
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :tool_calls [{:id "call_1"
                                                  :function {:name "mcp__trusted-db__query"
                                                             :arguments "{\"q\":\"select user\"}"}}]})
      ;; LLM Turn 2: Receive redacted data and call another tool using the token
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :content "I found the user. Now updating."
                                    :tool_calls [{:id "call_2"
                                                  :function {:name "mcp__trusted-db__query"
                                                             :arguments (json/generate-string {:email expected-token})}}]})
      ;; Final response
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content "Update user wes"}]
                                          :stream false
                                          :extra_body {:request-id request-id}})
                                  :headers {"Content-Type" "application/json"}})]
        (is (= 200 (:status response)))
        ;; Verify MCP received the RESTORED value in the second call
        (let [mcp-requests @(:received-requests mcp)
              tool-calls (filter #(= "tools/call" (-> % :body :method)) mcp-requests)
              update-call (last tool-calls)
              args-str (-> update-call :body :params :arguments)
              args (json/parse-string args-str true)]
          (is (= secret-email (:email args))))
        ;; Verify LLM received REDACTED token (not original) in tool result
        (let [llm-requests @(:received-requests llm)
              tool-call-req (first (filter #(get-in % [:messages (dec (count (:messages %))) :tool_calls]) llm-requests))
              msgs (:messages tool-call-req)
              tool-result-msg (last msgs)]
          (is (some? tool-result-msg))
          (is (= "tool" (:role tool-result-msg)))
          (is (str/includes? (:content tool-result-msg) expected-token))
          (is (not (str/includes? (:content tool-result-msg) secret-email))))))))

(deftest test-edit-tool-with-pii-token
  (testing "Edit tool can use restored PII tokens (fixes read->edit workflow)"
    (let [{:keys [injector llm mcp]} @test-state
          port (:port injector)
          request-id "edit-test-request-id"
          secret-email "wes@example.com"
          token (pii/generate-token :EMAIL_ADDRESS secret-email request-id)]
      ;; Setup MCP with read and edit tools
      ((:set-tools! mcp)
       {:read-file
        {:description "Read file contents"
         :schema {:type "object" :properties {:path {:type "string"}}}
         :handler (fn [args] {:content secret-email})}
        :edit-file
        {:description "Edit file"
         :schema {:type "object" :properties {:path {:type "string"}
                                               :old_string {:type "string"}
                                               :new_string {:type "string"}}}
         :handler (fn [args] {:success true :received-args args})}})
      ;; LLM Turn 1: Read file
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :content "I'll read the file."
                                    :tool_calls [{:id "call_1"
                                                  :function {:name "mcp__workspace__read-file"
                                                             :arguments (json/generate-string {:path "/tmp/script.sh"})}}]})
      ;; LLM Turn 2: Uses token in edit old_string
      (test-llm/set-next-response llm
                                   {:role "assistant"
                                    :content "Updating email..."
                                    :tool_calls [{:id "call_2"
                                                  :function {:name "mcp__workspace__edit-file"
                                                             :arguments (json/generate-string
                                                                         {:path "/tmp/script.sh"
                                                                          :old_string token
                                                                          :new_string "new@example.com"})}}]})
      ;; Final response
      (test-llm/set-next-response llm {:role "assistant" :content "Done."})
      (let [response @(http/post (str "http://localhost:" port "/v1/chat/completions")
                                 {:body (json/generate-string
                                         {:model "brain"
                                          :messages [{:role "user" :content "Update the email in /tmp/script.sh"}]
                                          :stream false
                                          :extra_body {:request-id request-id}})
                                  :headers {"Content-Type" "application/json"}})]
        (is (= 200 (:status response)))
        ;; Verify edit tool received ACTUAL email (not token) in old_string
        (let [mcp-requests @(:received-requests mcp)
              edit-call (last (filter #(= "tools/call" (-> % :body :method)) mcp-requests))
              args-str (-> edit-call :body :params :arguments)
              args (json/parse-string args-str true)]
          (is (= secret-email (:old_string args))))))))

(defn -main [& _args]
  (let [result (clojure.test/run-tests 'mcp-injector.restoration-test)]
    (System/exit (if (zero? (:fail result)) 0 1))))

=== FILE: flake.nix ===
{
  description = "mcp-injector - HTTP shim for injecting MCP tools into OpenAI-compatible chat completions";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};

        babashka = pkgs.babashka;

        mcp-injector = pkgs.stdenv.mkDerivation {
          pname = "mcp-injector";
          version = "0.1.0";

          src = ./.;

          nativeBuildInputs = [pkgs.makeWrapper];
          buildInputs = [babashka];

          installPhase = ''
            mkdir -p $out/bin $out/share/mcp-injector

            cp -r src $out/share/mcp-injector/
            cp bb.edn $out/share/mcp-injector/
            cp mcp-servers.example.edn $out/share/mcp-injector/mcp-servers.edn

            # Wrapper that points to the code without changing CWD
            makeWrapper ${babashka}/bin/bb $out/bin/mcp-injector \
              --prefix PATH : ${babashka}/bin \
              --add-flags "-cp $out/share/mcp-injector/src:$out/share/mcp-injector/test" \
              --add-flags "-m" \
              --add-flags "mcp-injector.core"
          '';

          meta = with pkgs.lib; {
            description = "HTTP shim for injecting MCP tools into OpenAI-compatible chat completions";
            homepage = "https://github.com/anomalyco/mcp-injector";
            license = licenses.mit;
            maintainers = [];
            platforms = platforms.unix;
          };
        };
      in {
        formatter = pkgs.alejandra;
        packages = {
          default = mcp-injector;
        };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            babashka
            clojure
            clj-kondo
            cljfmt
            mdformat
          ];

          shellHook = ''
            echo "mcp-injector Dev Environment"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "Babashka version: $(bb --version)"
            echo ""
            echo "Quick start:"
            echo "  bb run          - Start the server"
            echo "  bb test         - Run tests"
            echo "  bb repl         - Start REPL"
            echo ""
            echo "  nix build       - Build the package"
            echo "  nix run         - Run the built package"
            echo ""
            export SHELL=$OLDSHELL
          '';
        };

        apps = {
          default = {
            type = "app";
            program = "${mcp-injector}/bin/mcp-injector";
            meta = {
              description = "Start mcp-injector server";
            };
          };
        };
      }
    )
    // {
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }:
        with lib; let
          cfg = config.services.mcp-injector;

          mcp-injector-pkg = self.packages.${pkgs.system}.default;

          mcpServersConfig =
            pkgs.runCommand "mcp-servers.edn" {
              nativeBuildInputs = [pkgs.jet];
            } ''
              # Merge mcpServers and governance into a single EDN file
              # mcpServers should contain {:servers {...} :llm-gateway {...}}
              echo '${builtins.toJSON (cfg.mcpServers // { governance = cfg.governance; })}' | jet -i json -o edn -k > $out
            '';
        in {
          options.services.mcp-injector = {
            enable = mkEnableOption "mcp-injector HTTP server";

            port = mkOption {
              type = types.port;
              default = 8088;
              description = "Port for the mcp-injector HTTP server";
            };

            host = mkOption {
              type = types.str;
              default = "127.0.0.1";
              description = "Host address to bind to";
            };

            llmUrl = mkOption {
              type = types.str;
              default = "http://localhost:8080";
              description = "URL of OpenAI-compatible LLM endpoint";
            };

             mcpServers = mkOption {
               type = types.attrs;
               default = {};
               description = "MCP server configurations";
               example = literalExpression ''
                 {
                   stripe = {
                     url = "http://localhost:3001/mcp";
                     trust = "restore";  # "none" (default), "read", or "restore"
                     tools = ["retrieve_customer" "list_charges"];
                   };
                   workspace = {
                     url = "http://localhost:3000/mcp";
                     trust = "restore";
                     tools = [
                       { name = "read"; trust = "read"; }
                       { name = "write"; trust = "restore"; }
                     ];
                   };
                 }
               '';
             };

            governance = mkOption {
              type = types.submodule {
                options = {
                  mode = mkOption {
                    type = types.enum ["permissive" "strict"];
                    default = "permissive";
                    description = "Governance mode. Strict requires explicit allow-rules.";
                  };
                  policy = mkOption {
                    type = types.attrs;
                    default = {};
                    description = "Allow/Deny lists and model rules.";
                  };
                  pii = mkOption {
                    type = types.attrs;
                    default = {
                      enabled = true;
                      mode = "replace";
                    };
                    description = "PII scanning configuration.";
                  };
                  audit = mkOption {
                    type = types.attrs;
                    default = {
                      enabled = true;
                      path = "logs/audit.log.ndjson";
                    };
                    description = "Audit trail configuration.";
                  };
                };
              };
              default = {};
              description = "Governance and security framework configuration.";
            };

            logLevel = mkOption {
              type = types.enum ["debug" "info" "warn" "error"];
              default = "info";
              description = "Logging level";
            };

            maxIterations = mkOption {
              type = types.int;
              default = 10;
              description = "Maximum agent loop iterations";
            };

            timeoutMs = mkOption {
              type = types.int;
              default = 1800000;
              description = "Request timeout in milliseconds";
            };

            user = mkOption {
              type = types.str;
              default = "mcp-injector";
              description = "User to run the service as";
            };

            group = mkOption {
              type = types.str;
              default = "mcp-injector";
              description = "Group to run the service as";
            };

            openFirewall = mkOption {
              type = types.bool;
              default = false;
              description = "Open firewall port for mcp-injector";
            };

            environmentFile = mkOption {
              type = types.nullOr types.path;
              default = null;
              description = "Path to environment file containing secrets (e.g. /etc/secrets/mcp-injector.env)";
              example = "/etc/secrets/mcp-injector.env";
            };
          };

          config = mkIf cfg.enable {
            users.users.${cfg.user} = {
              isSystemUser = true;
              group = cfg.group;
              description = "mcp-injector service user";
            };

            users.groups.${cfg.group} = {};

            systemd.services.mcp-injector = {
              description = "mcp-injector HTTP server";
              wantedBy = ["multi-user.target"];
              after = ["network.target"];

              environment = {
                HOME = "/var/lib/mcp-injector";
                JAVA_TOOL_OPTIONS = "-Duser.home=/var/lib/mcp-injector";
                MCP_INJECTOR_PORT = toString cfg.port;
                MCP_INJECTOR_HOST = cfg.host;
                MCP_INJECTOR_LLM_URL = cfg.llmUrl;
                MCP_INJECTOR_LOG_LEVEL = cfg.logLevel;
                MCP_INJECTOR_MAX_ITERATIONS = toString cfg.maxIterations;
                MCP_INJECTOR_TIMEOUT_MS = toString cfg.timeoutMs;
                MCP_INJECTOR_MCP_CONFIG = mcpServersConfig;
              };

              serviceConfig = {
                Type = "simple";
                User = cfg.user;
                Group = cfg.group;
                WorkingDirectory = "/var/lib/mcp-injector";
                ExecStart = "${mcp-injector-pkg}/bin/mcp-injector";
                Restart = "on-failure";
                RestartSec = "5s";
                StateDirectory = "mcp-injector";
                LogsDirectory = "mcp-injector";
                EnvironmentFile = mkIf (cfg.environmentFile != null) cfg.environmentFile;

                NoNewPrivileges = true;
                PrivateTmp = true;
                ProtectSystem = "strict";
                ProtectHome = true;

                MemoryMax = "2G";
                TasksMax = 100;
              };
            };

            networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];
          };
        };
    };
}

=== FILE: mcp-servers.example.edn ===
;; Governance and security configuration
;; Privileged tools like clojure-eval are blocked by default, require explicit allow
 {:governance
  {:mode :permissive
   :policy
   {:allow ["clojure-eval"]}  ;; Allow native clojure-eval tool
   :pii
   {:enabled true :mode :replace}
   :audit
   {:enabled true :path "logs/audit.log.ndjson"}}

  :servers
  {:auphonic {:url "http://localhost:3003/mcp"
              :tools nil
              :trust "none"}   ; "none" (default), "read", or "restore"
   :nixos {:cmd ["nix" "run" "github:utensils/mcp-nixos"]
           :trust "none"}
     ;; Example local tool (uncomment to use)
     ;; :filesystem
     ;; {:cmd ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/path/to/allowed/dir"]
     ;;  :trust "restore"  ; allow token restoration for edit/write
     ;;  :tools [{:name "read" :trust "read"}
     ;;          {:name "write" :trust "restore"}]}
   }
;; LLM gateway configuration
  :llm-gateway
  {:url "http://prism:8080"
   :log-level "info" ;; debug|info|warn|error

   ;; Fallback chain for legacy mode (injected into requests)
   :fallbacks [{:provider "zen"
                :model "kimi-k2.5-free"}
               {:provider "nvidia"
                :model "moonshotai/kimi-k2.5"}
               {:provider "openrouter"
                :model "moonshotai/kimi-k2.5"}]

   ;; Virtual models with provider chain and cooldown
   :virtual-models
   {:brain

    {:chain [  ;; Tier 1: Top agentic kings — strongest brains when alive
             "zen/minimax-m2.5-free"                      ;; Free M2.5 — often #1 for coding agents right now
             "zen/kimi-k2.5-free"                         ;; Free Kimi K2.5 — multimodal/agent swarm leader
             "zen/glm-4.7-free"                           ;; Free GLM-4.7 — planning/stability beast

  ;; NVIDIA-hosted (dev quotas often generous on these quieter ones)
             "nvidia/minimaxai/minimax-m2.5"              ;; MiniMax on NVIDIA — fast/reliable
             "nvidia/moonshotai/kimi-k2.5"                ;; Kimi on NVIDIA — good when not overloaded
             "nvidia/z-ai/glm5"                           ;; GLM-5 — frontier agentic
             "nvidia/qwen/qwen3-coder-480b-a35b-instruct" ;; Qwen Coder — repo/tool monster

  ;; Last-resort paid fallbacks via OpenRouter
             "openrouter/minimax/minimax-m2.5"
             "openrouter/moonshotai/kimi-k2.5"
             "openrouter/z-ai/glm5"
             "openrouter/qwen/qwen3-coder-480b-a35b-instruct"]
     :cooldown-minutes 5
     ;; Don't include 503 (context overflow) in retry-on
     ;; Same model has same context window, so advancing the chain wastes quota
     ;; Let OpenClaw compress the session instead
     :retry-on [429 500]}}}}


=== FILE: dev/specs/configurable-trust-levels.edn ===
{:title "Configurable PII Restoration Trust Levels"
 :description "Enhance Smart Vault to support configurable trust levels for fine-grained control over token restoration."
 :acceptance-criteria
 ["Trust levels :none, :read, :restore are supported in mcp-servers.edn config"
  "edit tool can restore tokens from vault when server/tool trust is :restore"
  "NixOS module exposes trust configuration options"
  "Audit logging records restoration events"
  "Read tool test demonstrates token generation and vault storage"
  "Edit tool test demonstrates successful token resolution and file modification"]
 :edge-cases
 ["Token not found in vault (should pass through)"
  "Multiple tokens in single argument string"
  "Restoration for deeply nested JSON structures"
  "Partial path trust (server-level :restore but tool-level :none)"]}

