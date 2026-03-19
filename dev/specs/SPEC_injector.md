# SPEC: mcp-injector - Transparent Kernel & Loop Preservation

## Status: 🟢 DRAFT / READY FOR REVIEW
**Updated:** 2026-03-19

## 1. Objective
Eliminate the history bottleneck by ensuring every internal agentic loop turn is reported back to the client in a spec-compliant, neutral format.

## 2. Core Requirements

### 2.1 Epistemological Loop Preservation
- **State Tracking**: Modify the `agent-loop` in `core.clj` to accumulate a `WorkLog` vector of every message generated during the loop.
- **Reasoning Anchoring**: If a provider returns `reasoning_content`, the Injector must wrap it in `<thought>` tags and prepend it to the `:content` field before saving to the `WorkLog`.
- **Granular Data**: Ensure `WorkLog` entries include literal tool results and reasoning.

### 2.2 The JSON-in-Comment Protocol
- **Final Response Construction**: The final assistant message returned to the client must append a metadata footer.
- **Format**: 
  ```
  [Summary Response]

  ;; injector-history: {"turns": [...]}
  ```
- **Robustness**: Use `cheshire` for JSON serialization to ensure all nested content is correctly escaped within the string payload.

### 2.3 Server-Side Re-hydration
- **LRU Cache**: Maintain an in-memory cache of active `WorkLog` vectors keyed by `session-id`.
- **Interrupt Handling**: If a client disconnects mid-loop, save the current state.
- **Re-hydration**: When a new request arrives with a known `session-id`, automatically prepend the cached history to the new request context.

## 3. Implementation Tasks
- [ ] Update `agent-loop` to return `{:final-message m :turns [...]}`.
- [ ] Implement reasoning-to-content baking logic.
- [ ] Update `openai_compat.clj` to append the `;; injector-history` footer.
- [ ] Implement the LRU cache for session re-hydration.
- [ ] Add robust `session-id` detection from `extra_body`.

## 4. Notes & Questions
- **Token Management**: Should we implement basic truncation for extremely large tool results (e.g., >10KB) before inlining in the history?
- **Decoupling**: Should the prefix be generic `;; sync:` to support other kernels?
