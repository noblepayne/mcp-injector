# Clojure Code Review Guide

A practical guide for reviewing Clojure code with a functional, pragmatic, principled approach.

---

## 1. Key Principles

### Core Philosophy
- **Readability first**: Code is read more than written. Optimize for human comprehension.
- **Consistency over style**: Follow project conventions first, style guide second.
- **Practical purity**: Push effects to boundaries, don't achieve purity for its own sake.
- **Data over objects**: Prefer maps and sequences over custom types until complexity demands otherwise.
- **Small functions, single responsibility**: Functions should do one thing well.

### Reviewer Mindset
- Ask "what problem does this solve?" before "how is it written?"
- Distinguish between issues that block merge vs. suggestions for improvement
- Consider whether the code fits the project's established patterns
- Remember: the goal is working, maintainable code—not perfect code

---

## 2. Naming Conventions

| Type | Convention | Example |
|------|------------|---------|
| Functions/vars | `kebab-case` | `full-name` |
| Namespaces | `kebab-case` | `my.project.module` |
| Protocols/records | `PascalCase` | `UserService` |
| Predicates | end with `?` | `valid?` |
| Dynamic vars | `*earmuffs*` | `*request-id*` |
| Unsafe/STM-unsafe | end with `!` | `stop-server!` |

### Naming Rules
- **Verbs for side effects**: `create-order`, `send-email`, `fetch-user`
- **Nouns for pure functions**: `age`, `full-name`, `total-price` (Stuart Sierra's style)
- **Acronyms stay uppercase**: HTTP, XML, URL
- **Avoid repeating namespace**: `products/price` not `products/product-price`
- **Meaningful aliases**: `[clojure.string :as str]` not `s`

---

## 3. Threading Decision Tree

```
Is data flowing through a series of transformations?
│
├─ YES: Use threading
│   ├─ First arg flows: `->`
│   ├─ Last arg flows: `->>`
│   └─ Conditional steps: `cond->`, `cond->>`
│
├─ MIXED/EXCEPTIONAL: Consider `as->` in threading
│   └─ Otherwise prefer `let`
│
└─ NO: Use direct function composition
    └─ `(comp f g h)` for `(fn [x] (f (g (h x))))`
```

### Threading Anti-Patterns
```clojure
;; Prefer for performance with large collections:
(sequence (comp (map f) (filter p) (map g)) coll)

;; Avoid consecutive lazy ops on large data:
(->> coll (map f) (filter p) (map g))

;; Don't over-thread when it obscures meaning
```

---

## 4. State Primitive Selection

```
Need to update state?
│
├─ SINGLE VALUE, NO COORDINATION
│   └─ atom (swap!, reset!)
│
├─ MULTIPLE VALUES TOGETHER
│   └─ refs in dosync transaction
│
├─ ASYNC, INDEPENDENT
│   └─ agent (send, send-off)
│
└─ THREAD-LOCAL, DYNAMIC SCOPE
    └─ dynamic var (binding)
```

---

## 5. Red Flags

### Critical Issues (Block Merge)
- Global mutable state without clear ownership
- Concurrency bugs: race conditions, non-atomic updates
- Resource leaks: unclosed connections, unstopped threads
- Security issues: injection, exposed secrets, improper auth
- `def` inside functions (creates vars at runtime)

### Code Smells (Suggestions)
- Deep nesting (>3-4 levels)
- `lazy-seq` + `concat` chains (stack overflow risk)
- Lazy sequences with side effects: `(map prn coll)`
- Numbered lambda params (`%1`, `%2`, `%3`)
- Complex `#()` where `fn` with names would be clearer
- Functions >40 lines without clear sections
- Missing error handling on I/O operations
- Overuse of `atom` where calculation would suffice

### Naming Issues
- Meaningless names: `temp`, `data`, `result`
- Name collision with core functions without distinction
- Inconsistent naming within same context

---

## 6. Stuart Sierra's Clojure Don'ts

1. **Never `(map prn coll)`** — lazy + side effects are unpredictable
2. **No `concat` in loops** — creates stack overflows
3. **Don't use `#()` for complex functions** — use `fn` with named params
4. **No numbered parameters** (`%1`, `%2`) — makes code harder to read
5. **Don't use `as->` by itself** — use `let` instead
6. **Don't over-private** — Clojure trusts developers
7. **Don't fight the platform** — use Java interop when appropriate

---

## 7. Error Handling Review

**Questions to ask:**
- [ ] Exceptions used for truly exceptional cases, not control flow?
- [ ] Specific exceptions caught, not bare `catch Exception`?
- [ ] Error messages are meaningful and actionable?
- [ ] Errors logged appropriately (not swallowed)?
- [ ] Could Result/Either pattern be clearer for expected failures?
- [ ] Are pre/post conditions used for critical invariants?

---

## 8. Testing Review

**What to check:**
- [ ] Tests verify behavior, not implementation
- [ ] Tests are deterministic (no reliance on time/random without seeding)
- [ ] Error cases tested, not just happy path
- [ ] Complex pure functions have generative tests (test.check)
- [ ] Integration tests cover critical paths
- [ ] Test names describe what they verify

**Testing anti-patterns:**
- Tests that only pass by mocking everything
- Tests coupled to implementation details
- No tests for pure functions

---

## 9. Namespace Organization

**Questions:**
- [ ] One namespace per file?
- [ ] File path matches namespace (`my.project.module` → `my/project/module.clj`)?
- [ ] `:require` over bare `require`/`use`?
- [ ] `refer :all` avoided except in test namespaces?
- [ ] Related functions grouped together?

---

## 10. Library Selection

**Questions:**
- [ ] Is this library truly necessary?
- [ ] Three-line utility copied into project > new dependency?
- [ ] Is the library actively maintained?
- [ ] Does it have a compatible license?
- [ ] Does it pull in many transitive dependencies?

**Established libraries by domain:**
- HTTP: Ring, Pedestal, http-kit
- SQL: HoneySQL, HugSQL
- Config: Aero
- Lifecycle: Component, Integrant
- JSON: Cheshire
- HTTP Client: babashka.http-client, clj-http

---

## 11. Quick Reference Card

```
NAMING
  functions:  kebab-case, verbs for effects, nouns for values
  predicates: ends with ?
  dynamic:    *earmuffs*
  unsafe:     ends with !

THREADING
  first arg flows:   ->
  last arg flows:    ->>
  conditional:       cond->, cond->>
  complex:           let

STATE
  single value:     atom
  coordinated:      refs + dosync
  async:            agent
  thread-local:     binding/dynamic var

DON'T
  (map prn coll)
  concat in loops
  complex #()
  %1, %2 params
  as-> by itself
```

---

## 12. Praise List

**Design wins to encourage:**
- Elegant data transformations with map/filter/reduce/transducers
- Good abstraction boundaries: pure core with I/O at edges
- Composable functions: small pieces that fit together
- Thoughtful naming: functions that clearly communicate intent
- Idiomatic Clojure: using the "Clojure way"

**Code quality wins:**
- Clear intent: code reads like documentation
- Appropriate threading: data flow is obvious
- Good error handling: graceful failure with clear messages
- Smart use of protocols/multimethods
- Minimal, correct, explicit state management

**Pragmatic choices:**
- Simplicity over cleverness
- Practical abstractions for current need
- Appropriate use of language features (not showing off)
- Good dependency hygiene
