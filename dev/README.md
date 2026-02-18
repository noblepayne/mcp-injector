# Project Tracking

This directory contains the project state and tracking for mcp-injector development.

## Files

### `backlog.edn`
Master task list with all planned work. Tasks include:
- `:id` - Keyword identifier
- `:title` - Short description
- `:description` - Full description
- `:status` - :todo, :in-progress, :done, or :cancelled
- `:priority` - :high, :medium, :low
- `:phase` - Which phase (1, 2, 3...)
- `:tags` - List of keywords
- `:depends-on` - List of task IDs this depends on
- `:commits` - Git commits related to this task

### `current.edn`
Current session state:
- Active tasks
- Session notes
- Next steps
- Progress tracking

### `log.md`
Running development log with:
- Daily/session entries
- Decisions made
- Blockers encountered
- Technical details

### `decisions.edn`
Architecture Decision Records (ADRs):
- Why we chose particular approaches
- Alternatives considered
- Consequences of decisions

## Workflow

1. **Before working**: Check `current.edn` for active tasks
2. **Start task**: Update status in `backlog.edn` and `current.edn`
3. **During work**: Update `log.md` with notes and decisions
4. **Complete task**: 
   - Mark as `:done` in both EDN files
   - Add commit SHAs to `:commits`
   - Create changelog fragment in `changelog/unreleased/`
5. **End session**: Update `current.edn` with completed work and next steps

## Changelog

Changelog fragments go in `changelog/unreleased/*.edn`:

```clojure
{:type :added        ; :added :changed :deprecated :removed :fixed :security
 :description "What changed"
 :issue nil          ; Issue number if applicable
 :author "who"}
```

At release time, fragments are compiled into CHANGELOG.md.
