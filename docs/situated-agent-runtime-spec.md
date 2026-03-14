# Situated Agent Runtime (SAR)

**A production-ready agent orchestration layer for business automation**

## The Problem

The AI agent ecosystem is fragmented and doesn't serve actual businesses:

- **OpenClaw**: Great conversational UX (chat, memory, scheduling) but terrible API integration

  - Forces `stream=true` on all requests
  - Janky TypeScript shims to call MCP servers
  - Goes through OpenCode just to hit MCP tools

- **OpenCode**: Excellent for dev environments with stdio MCP servers

  - Optimized for coding in an IDE
  - Stdio-first, not production HTTP APIs
  - Wrong abstraction for business workflows

- **MCP Gateways** (Bifrost, etc.): Try to do too much

  - Either inject ALL tools (token bloat) or hide them in code execution (opaque)
  - Tightly coupled to their opinionated execution models

**What's missing:** A runtime that lets you plug a conversational agent into production HTTP APIs with progressive tool discovery, proper workflows (Skills), and clean composition.

## The User

You run a business (podcasting, e-commerce, SaaS, whatever). You have:

- Stripe, Shopify, Twilio, GitHub, Slack, Postgres, etc.
- A need to automate workflows across these services
- Production systems that need monitoring and management
- Research tasks, content creation, operational work

You want an agent to **DO SHIT** with those APIs, not just chat about them.

## The Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ OpenClaw                                                    │
│ (Telegram/Discord/WhatsApp interface)                      │
│ - Conversational UX                                         │
│ - Memory & context                                          │
│ - Cron jobs & reminders                                     │
│ - ALWAYS sends stream=true                                  │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ Situated Agent Runtime (THIS PROJECT)                      │
│                                                             │
│ 1. Strip stream=true from OpenClaw requests                │
│ 2. Inject MCP tool directory (progressive disclosure)      │
│ 3. Add get_tool_schema(mcp, tool) meta-tool               │
│ 4. Run agent loop: LLM → tools → LLM → tools...           │
│ 5. Execute MCP tool calls via HTTP                         │
│ 6. Convert response back to SSE for OpenClaw               │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ Bifrost (or any OpenAI-compatible LLM router)              │
│ - Fast LLM routing/load balancing (11µs overhead)          │
│ - Model inference ONLY                                      │
│ - No MCP awareness needed                                   │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ HTTP MCP Servers                                            │
│ - Native HTTP MCP implementations                           │
│ - Stdio servers wrapped via mcp-proxy                       │
│ - Custom business logic servers                             │
│                                                             │
│ Examples: stripe-mcp, github-mcp, postgres-mcp,           │
│           podcast-api-mcp, slack-mcp, etc.                 │
└─────────────────────────────────────────────────────────────┘
```

## Core Concepts

### 1. Progressive Tool Discovery

**The token bloat problem:** Injecting full schemas for 20+ MCP servers costs thousands of tokens.

**The solution:** Hybrid injection strategy

```
System Prompt Injection:
┌──────────────────────────────────────────────────────────┐
│ Available MCP Tools:                                     │
│                                                          │
│ Common (full schemas included):                          │
│   filesystem: read(path), write(path, content), ls(dir) │
│   brave_search: search(query), local(query)             │
│   memory: store(key, value), retrieve(key)              │
│                                                          │
│ Available via get_tool_schema(mcp, tool):                │
│   stripe: retrieve_customer, list_charges, create_refund│
│   shopify: get_orders, update_inventory, create_product │
│   postgres: query, execute, list_tables                  │
│   github: create_issue, comment, search, list_prs       │
│   slack: send_message, list_channels, upload_file       │
│   twilio: send_sms, make_call, list_messages            │
│   podcast_api: upload_episode, get_analytics, schedule  │
└──────────────────────────────────────────────────────────┘

Meta-tool available:
  get_tool_schema(mcp_name: str, tool_name: str) → full_schema
```

**How it works:**

1. Agent sees compact directory, reasons about which tools to use
1. Calls `get_tool_schema("stripe", "retrieve_customer")`
1. Gets full schema with parameters, types, descriptions
1. Makes actual tool call with proper arguments
1. Schema stays in context for subsequent calls (no re-fetch)

**Token savings:**

- Full injection: ~2000 tokens for all Stripe tools
- Progressive: ~50 tokens directory + ~200 tokens per schema on-demand
- Only pay for what you use

### 2. Skills as Workflow Orchestration

**Skills ≠ Tools**

- **MCP** = What primitives are available (low-level API calls)
- **Skills** = How to use those primitives effectively (workflows, best practices)

**Example: `podcast-publishing.md`**

```markdown
---
name: podcast-publishing
description: Publish and promote podcast episodes across platforms
---

# Podcast Publishing Workflow

When the user wants to publish an episode:

1. **Validate episode data**
   - Use get_tool_schema("podcast_api", "validate_episode")
   - Check: audio file exists, metadata complete, cover art present
   
2. **Upload to hosting**
   - Call podcast_api.upload_episode(file, metadata)
   - Wait for transcoding completion
   - Store episode_id in memory for later reference

3. **Update RSS feed**
   - Call podcast_api.publish_to_rss(episode_id)
   - Verify feed validates (use podcast_api.validate_rss)

4. **Cross-post to platforms**
   - YouTube: Extract key moments, create video (use video_mcp)
   - LinkedIn: Write professional summary (use llm, max 280 chars)
   - Twitter: Create thread with highlights
   
5. **Analytics tracking**
   - Store publish timestamp in postgres
   - Set reminder to check analytics in 24h (use memory.store)

## Best Practices

- Always validate before uploading (prevents costly rollbacks)
- Use postgres to track publish history (for analytics)
- Never expose internal episode IDs in social posts
- Check rate limits before bulk posting
```

### 3. Agent Loop Execution

The runtime handles the full agent loop internally:

```clojure
(defn execute-agent-loop [initial-messages tools]
  (loop [messages initial-messages
         iterations 0]
    (if (>= iterations max-iterations)
      {:error "Max iterations reached"}
      (let [response (call-llm messages tools)
            tool-calls (:tool_calls response)]
        (if (empty? tool-calls)
          ;; No more tools to call, return final response
          response
          ;; Execute tools and continue loop
          (let [tool-results (execute-tool-calls tool-calls)
                new-messages (concat messages
                                   [(assoc response :role "assistant")]
                                   (map tool-result->message tool-results))]
            (recur new-messages (inc iterations))))))))
```

**Key behaviors:**

- Max iterations limit (default: 10)
- Tool results added to conversation context
- Fetched schemas cached in context
- Errors handled gracefully with fallback

### 4. SSE Streaming Translation

OpenClaw expects Server-Sent Events (SSE) format, even though we do synchronous tool execution.

**Minimal valid SSE stream (3 chunks):**

```javascript
// 1. Role announcement
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1234567890,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

// 2. Content (can be single blob or chunked)
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1234567890,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Full response here"},"finish_reason":null}]}

// 3. Finish marker
data: {"id":"chatcmpl-123","object":"chat.completion.chunk","created":1234567890,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

// 4. Done signal
data: [DONE]

```

**For tool calls:**

```javascript
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"stripe.retrieve_customer","arguments":"{\"customer_id\":\"cus_123\"}"}}]}}]}

data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

data: [DONE]
```

**Implementation strategy:**

- Start with single-blob approach (simple, works fine)
- Optionally chunk by words (50-100 words/chunk) for better UX
- No complex buffering needed - this is transactional work, not creative writing

## Technical Implementation

### Why Babashka?

Babashka is perfect for this runtime:

1. **Single binary** - Embed scripting in a ~100MB executable
1. **Fast startup** - \<100ms, perfect for request/response
1. **HTTP built-in** - `http-kit` or `ring` adapters available
1. **SSE support** - Can stream responses easily
1. **JSON/EDN native** - Parse/generate without heavyweight libraries
1. **Shell integration** - Easy to call `curl` for MCP HTTP calls
1. **JVM interop when needed** - Access Java libs if required
1. **Great for ops** - Clojure's data orientation perfect for API orchestration

### Core Modules

```
situated-agent-runtime/
├── bb.edn                    # Babashka config
├── src/
│   ├── sar/
│   │   ├── core.clj         # Main entry point, HTTP server
│   │   ├── openai_compat.clj # SSE formatting, request/response translation
│   │   ├── agent_loop.clj   # Agent execution loop
│   │   ├── mcp_client.clj   # HTTP MCP client
│   │   ├── tool_discovery.clj # Progressive schema fetching
│   │   ├── skills.clj       # Skills loading and injection
│   │   └── config.clj       # MCP server registry, settings
├── mcp-servers.edn          # MCP server configurations
├── skills/                  # Skills directory
│   ├── podcast-publishing.md
│   ├── customer-support.md
│   └── analytics-reporting.md
└── README.md
```

### MCP Server Configuration

```clojure
;; mcp-servers.edn
{:servers
 {:stripe
  {:url "http://localhost:3001/mcp"
   :tools ["retrieve_customer" "list_charges" "create_refund"]
   :inject :lazy}  ;; :full, :lazy, or :none
  
  :postgres
  {:url "http://localhost:3002/mcp"
   :tools ["query" "execute" "list_tables"]
   :inject :lazy}
  
  :filesystem
  {:url "http://localhost:3003/mcp"
   :tools ["read" "write" "ls"]
   :inject :full}  ;; Always inject full schemas
  
  :podcast-api
  {:url "http://localhost:3004/mcp"
   :tools ["upload_episode" "validate_episode" "publish_to_rss"]
   :inject :lazy}}}
```

### Request Flow

```clojure
(ns sar.core
  (:require [org.httpkit.server :as http]
            [cheshire.core :as json]
            [sar.openai-compat :as openai]
            [sar.agent-loop :as agent]))

(defn handle-chat-completion [request]
  (let [body (json/parse-string (slurp (:body request)) true)
        ;; Strip stream flag
        clean-request (assoc body :stream false)
        
        ;; Inject MCP directory
        messages (openai/inject-mcp-directory (:messages clean-request))
        
        ;; Add meta-tools
        tools (concat (:tools clean-request)
                     [(openai/get-tool-schema-definition)])
        
        ;; Run agent loop
        result (agent/execute-loop messages tools)
        
        ;; Convert to SSE
        sse-response (openai/to-sse-stream result)]
    
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"
               "Connection" "keep-alive"}
     :body sse-response}))

(defn -main [& args]
  (http/run-server
    (fn [request]
      (case [(:request-method request) (:uri request)]
        [:post "/v1/chat/completions"] (handle-chat-completion request)
        {:status 404 :body "Not found"}))
    {:port 8080})
  (println "Situated Agent Runtime listening on port 8080"))
```

### MCP Client

```clojure
(ns sar.mcp-client
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]))

(defn call-mcp-tool
  "Call an MCP tool via HTTP POST"
  [mcp-server tool-name args]
  (let [server-config (get-server-config mcp-server)
        url (:url server-config)
        payload {:jsonrpc "2.0"
                 :id (str (random-uuid))
                 :method "tools/call"
                 :params {:name tool-name
                         :arguments args}}
        response (curl/post url
                           {:body (json/generate-string payload)
                            :headers {"Content-Type" "application/json"}})]
    (json/parse-string (:body response) true)))

(defn get-tool-schema
  "Fetch schema for a specific tool from MCP server"
  [mcp-server tool-name]
  (let [server-config (get-server-config mcp-server)
        url (:url server-config)
        payload {:jsonrpc "2.0"
                 :id (str (random-uuid))
                 :method "tools/list"
                 :params {}}
        response (curl/post url
                           {:body (json/generate-string payload)
                            :headers {"Content-Type" "application/json"}})
        tools (-> response :body (json/parse-string true) :result :tools)]
    (first (filter #(= tool-name (:name %)) tools))))
```

### Agent Loop with Schema Caching

```clojure
(ns sar.agent-loop
  (:require [sar.mcp-client :as mcp]))

(def schema-cache (atom {}))

(defn execute-tool-call
  "Execute a single tool call (MCP or meta-tool)"
  [tool-call]
  (let [{:keys [name arguments]} tool-call]
    (cond
      ;; Meta-tool: fetch schema
      (= name "get_tool_schema")
      (let [{:keys [mcp_name tool_name]} arguments
            cache-key [mcp_name tool_name]
            cached-schema (get @schema-cache cache-key)]
        (if cached-schema
          {:cached true :schema cached-schema}
          (let [schema (mcp/get-tool-schema mcp_name tool_name)]
            (swap! schema-cache assoc cache-key schema)
            {:cached false :schema schema})))
      
      ;; MCP tool call
      :else
      (let [[mcp-name tool-name] (clojure.string/split name #"\." 2)]
        (mcp/call-mcp-tool mcp-name tool-name arguments)))))

(defn execute-loop
  "Main agent execution loop"
  [messages tools]
  (loop [msgs messages
         iteration 0]
    (if (>= iteration 10)
      {:error "Max iterations exceeded"}
      (let [response (call-llm msgs tools)
            tool-calls (:tool_calls response)]
        (if (empty? tool-calls)
          response
          (let [results (map execute-tool-call tool-calls)
                new-msgs (concat msgs
                              [(assoc response :role "assistant")]
                              (map #(hash-map :role "tool"
                                            :content (json/generate-string %))
                                   results))]
            (recur new-msgs (inc iteration))))))))
```

## Use Cases: Podcast Operations

### Episode Publishing

**User:** "Publish the new episode I uploaded yesterday"

**Agent execution:**

1. `memory.retrieve("recent_uploads")` → finds audio file path
1. `get_tool_schema("podcast_api", "validate_episode")` → fetches schema
1. `podcast_api.validate_episode(file_path)` → checks metadata
1. `podcast_api.upload_episode(...)` → uploads to host
1. `podcast_api.publish_to_rss(episode_id)` → updates feed
1. `slack.send_message("#team", "New episode live!")` → notifies team
1. `postgres.execute("INSERT INTO episodes ...")` → logs publish event

**Response:** "Published episode 'Understanding MCP' to RSS feed. Notified team in Slack. Episode ID: ep_abc123"

### Analytics Report

**User:** "How did last week's episodes perform?"

**Agent execution:**

1. `postgres.query("SELECT * FROM episodes WHERE ...")` → gets episode list
1. `podcast_api.get_analytics(episode_ids)` → fetches metrics
1. LLM generates summary with insights
1. `slack.send_message("#analytics", summary)` → shares with team

### Automated Promotion

**User:** "Schedule a promotional post for tomorrow's episode"

**Agent execution:**

1. `podcast_api.get_episode_details(next_episode_id)` → gets metadata
1. LLM writes engaging copy for different platforms
1. `memory.store("scheduled_posts", post_data)` → saves for cron job
1. Sets reminder via OpenClaw's scheduler

## Deployment

### Local Development

```bash
# Install Babashka
brew install borkdude/brew/babashka

# Run the runtime
bb run

# Test with curl
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-mini",
    "messages": [{"role": "user", "content": "List my Stripe customers"}],
    "stream": true
  }'
```

### Production

```dockerfile
# Dockerfile
FROM babashka/babashka:latest
WORKDIR /app
COPY . .
EXPOSE 8080
CMD ["bb", "run"]
```

```bash
# Docker deployment
docker build -t situated-agent-runtime .
docker run -p 8080:8080 \
  -e BIFROST_URL=http://bifrost:8081 \
  -e MCP_SERVERS_CONFIG=/config/mcp-servers.edn \
  situated-agent-runtime
```

### OpenClaw Configuration

```bash
# Point OpenClaw at your runtime instead of direct LLM
export OPENAI_API_BASE=http://localhost:8080/v1
export OPENAI_API_KEY=dummy  # Not used but required by SDK

# OpenClaw will now send all requests through SAR
openclaw start
```

## Roadmap

### Phase 1: Core Runtime ✅

- [ ] HTTP server with OpenAI-compatible endpoint
- [ ] SSE response formatting
- [ ] MCP HTTP client
- [ ] Basic agent loop
- [ ] get_tool_schema meta-tool

### Phase 2: Progressive Discovery

- [ ] MCP directory injection
- [ ] Schema caching
- [ ] Tool categorization (full vs lazy)
- [ ] Smart schema invalidation

### Phase 3: Skills Integration

- [ ] Skills directory loading
- [ ] AgentSkills-compatible format
- [ ] Dynamic skill injection based on query
- [ ] Skills marketplace/registry

### Phase 4: Production Hardening

- [ ] Error handling & retries
- [ ] Rate limiting
- [ ] Auth & security
- [ ] Observability (OpenTelemetry)
- [ ] Metrics & logging

### Phase 5: Advanced Features

- [ ] Multi-agent orchestration
- [ ] Workflow DAGs
- [ ] Human-in-the-loop approvals
- [ ] Cost tracking per operation
- [ ] A/B testing different prompts/models

## Why This Matters

**Current state:**

- AI agents are toys or dev tools
- Production API integration is janky
- Token costs spiral out of control
- Workflows are hard-coded or manual

**With SAR:**

- Conversational interface to your entire tech stack
- Progressive discovery keeps costs reasonable
- Skills codify institutional knowledge
- Clean composition of agent loops
- Production-ready from day one

**The vision:** Your agent should be able to:

- Monitor your podcast analytics and suggest optimizations
- Automatically handle customer support via Stripe + email
- Coordinate deploys across GitHub, AWS, and Slack
- Generate reports by querying multiple data sources
- Schedule and execute complex multi-step workflows

All through natural conversation, backed by production HTTP APIs, guided by your Skills, without token bloat or janky integrations.

## Contributing

This is open source (MIT). Contributions welcome for:

- MCP server implementations (especially HTTP-native)
- Skills for common workflows
- Performance optimizations
- Better error handling
- Documentation improvements

______________________________________________________________________

**Let's build agents that actually do shit.**
