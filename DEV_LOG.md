# DEV_LOG.md - Tool Auto-Discovery Feature

## Goal

Auto-discover tools from MCP servers at runtime instead of relying solely on config lists.

## Changes

### -19

#### Plan

- **2026-02Lazy + cached** approach: fetch tools on first request per server
- Filter by config:
  - `:tools nil` → discover all
  - `:tools []` → discover none (empty)
  - `:tools ["foo" "bar"]` → only those tools
- No cache invalidation (cache forever until restart)

#### Files to modify

1. `src/mcp_injector/mcp_client.clj` - add tool cache, discover-tools fns
1. `src/mcp_injector/config.clj` - update inject-tools-into-messages, build-tool-directory
1. `src/mcp_injector/core.clj` - wire up discovery

#### Tests to add

- Config nil → shows all discovered tools
- Config [] → shows none
- Config [specific] → shows only those

______________________________________________________________________

## Progress

- [x] Create DEV_LOG.md (this file)
- [x] Write tests for tool discovery filtering
- [x] Update mcp_client.clj with tool cache
- [ ] Update config.clj to use discovered tools (in progress)
- [ ] Run tests
- [ ] Lint and format

______________________________________________________________________

## Implementation Complete (2026-02-20)

### Summary

- Added `mcp/discover-tools` function with caching
- Added filtering by config `:tools` list:
  - `nil` → discover all
  - `[]` → discover none
  - `["foo" "bar"]` → only those
- Added error handling for unreachable MCP servers
- Updated config to accept pre-discovered tools
- Added tests for filtering behavior

### Tests Added

- `tool-discovery-filtering-nil-shows-all`
- `tool-discovery-filtering-empty-shows-none`
- `tool-discovery-filtering-specific-shows-only-those`
- `tool-discovery-caches-results`

______________________________________________________________________

## Additional Bug Report (2026-02-20)

**Issue:** MCP proxy with stdio server doing streamablehttp - complex output causes parsing errors.

**Error:**

```
Unexpected character ('_' (code 95)): was expecting comma to separate Object entries
```

**Root cause:** LLM is returning malformed JSON in tool arguments - looks like duplicate keys or bad JSON merging:

```
"arguments":"{\"operation\": \"insert\"_after\", ...
```

The `"_after"` suggests the LLM is outputting malformed JSON where `insert_after` got corrupted.

**Status:** Not yet investigated.

______________________________________________________________________

## STDIO Transport Support (2026-02-20)

### Research Summary

**Babashka/process** - available in bb.edn:

- `process` - creates subprocess with configurable stdin/stdout
- `alive?` - check if process running
- `destroy` / `destroy-tree` - kill process
- Supports env vars, working dir, etc.

**core.async in Babashka:**

- `go` blocks use thread pool (8 threads default), NOT true async
- `thread` - spawns real threads
- For many concurrent stdio connections, need to use `thread` or increase pool

### Design

#### Config Interface

```clojure
;; mcp-servers.edn
{:servers
 {:stripe
  ;; HTTP transport (existing)
  {:url "http://localhost:3000/mcp"
   :tools ["retrieve_customer"]}
  
  ;; STDIO transport (new)
  {:cmd "npx -y @modelcontextprotocol/server-filesystem /tmp/files"
   :env {:API_KEY "xxx"}  ;; optional env vars
   :cwd "/home/user"       ;; optional working dir
   :tools ["read_file" "write_file"]}}}

;; Transport detection: if :cmd or :command present → stdio, else → HTTP
```

#### Architecture

```
mcp_client.clj (dispatch)
    ├── mcp_client_http.clj (existing, http-kit)
    └── mcp_client_stdio.clj (new, babashka/process)
```

Both implement common protocol:

- `initialize! [server-config]` → session
- `list-tools [session]` → [tools]
- `call-tool [session tool-name args]` → result
- `close [session]` → nil

#### Process Management (mcp_client_stdio.clj)

```clojure
;; For each stdio server:
1. Spawn: (process {:in :pipe :out :pipe :err :pipe} "npx -y server")
2. Read stdout in thread (line-by-line JSON-RPC)
3. Write stdin (JSON-RPC requests)
4. Handle initialize handshake
5. On close: (destroy p)
```

#### Key Implementation Details

- **One process per server** - stdio is single-session
- **Thread per stdout reader** - read lines, parse JSON-RPC
- **Channels for responses** - correlate requests/responses by id
- **Process lifecycle** - start on first use, keep alive, destroy on shutdown

#### Dependencies

Add to bb.edn:

```clojure
babashka/process {:git/url "https://github.com/babashka/process"
                 :sha "..."}
```

#### Testing

- `test_mcp_stdio_server.clj` - spawns a simple stdio MCP server (like test_mcp_server but stdio)
- Same test patterns as HTTP tests

### Tasks

- [ ] Add babashka/process dependency
- [ ] Create mcp_client_stdio.clj with process management
- [ ] Update mcp_client.clj to dispatch by transport
- [ ] Update config.clj to detect transport type
- [ ] Add stdio tests
- [ ] Integration tests with both transports
