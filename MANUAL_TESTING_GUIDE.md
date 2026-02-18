# Manual Testing Guide: OpenClaw → mcp-injector → LLM gateway

**Temporary Guide - Not for Commit**

This guide walks you through manually testing the mcp-injector shim between your existing OpenClaw and LLM gateway setups.

---

## Prerequisites

- Working OpenClaw instance
- Working LLM gateway instance (with access to zen/nvidia/openrouter)
- mcp-injector built and ready to run

---

## Step 1: Configure mcp-servers.edn

Create or update `mcp-servers.edn` in the mcp-injector directory:

```clojure
{:servers
  ;; Optional: Add MCP servers here if you want tool injection
  ;; Leave empty for pure shim mode (no MCP tools)
  {}
 
 ;; LLM gateway fallback chain
 :llm
 {:fallbacks [{:provider "zen"
               :model "kimi-k2.5-free"}
              {:provider "nvidia" 
               :model "moonshotai/kimi-k2.5"}
              {:provider "openrouter"
               :model "moonshotai/kimi-k2.5"}]}}
```

**Note:** For initial testing, leave `:servers` empty. This tests the pure shim mode (stream stripping + fallbacks only).

---

## Step 2: Start mcp-injector

```bash
# In the mcp-injector directory
nix develop
bb run
```

Or with explicit config:
```bash
MCP_INJECTOR_PORT=8080 \
MCP_INJECTOR_BIFROST_URL=http://localhost:8081 \
MCP_INJECTOR_MCP_CONFIG=./mcp-servers.edn \
bb run
```

**Verify it's running:**
```bash
curl http://localhost:8080/health
# Should return: {"status":"ok"}
```

---

## Step 3: Configure OpenClaw

Update your OpenClaw configuration to point to mcp-injector instead of LLM gateway directly:

**Before:**
```
LLM_URL=http://localhost:8081/v1/chat/completions
```

**After:**
```
LLM_URL=http://localhost:8080/v1/chat/completions
```

**Restart OpenClaw** to pick up the new URL.

---

## Step 4: Test Basic Functionality

### 4.1 Simple Chat (No Tools)

Ask OpenClaw a simple question:
```
You: Hello, how are you?
```

**Expected:** Normal response from LLM via shim

**Check mcp-injector logs:**
- Should see request received
- Should see successful response

### 4.2 Verify Fallbacks Are Working

Check LLM gateway logs or metrics to confirm fallbacks array is being received.

The request to LLM gateway should include:
```json
{
  "model": "...",
  "messages": [...],
  "stream": false,
  "fallbacks": [
    {"provider": "zen", "model": "kimi-k2.5-free"},
    {"provider": "nvidia", "model": "moonshotai/kimi-k2.5"},
    {"provider": "openrouter", "model": "moonshotai/kimi-k2.5"}
  ]
}
```

### 4.3 Test Stream Conversion

OpenClaw sends `stream=true`, but LLM gateway receives `stream=false`.

The response from mcp-injector to OpenClaw should be SSE format:
```
data: {"id":"...","object":"chat.completion.chunk",...}

data: {"id":"...","object":"chat.completion.chunk",...}

data: [DONE]
```

---

## Step 5: Test Error Handling

### 5.1 Rate Limit Scenario

If you have a way to trigger rate limits in LLM gateway (or temporarily configure a low limit):

**Expected:** OpenClaw receives 429 error with proper error message, doesn't crash.

### 5.2 LLM gateway Down Scenario

Temporarily stop LLM gateway:
```bash
# Stop your LLM gateway instance
```

Ask OpenClaw a question.

**Expected:** 
- mcp-injector returns 503 or 504
- Error message: "Failed to reach LLM gateway" or "LLM gateway timeout"
- OpenClaw handles gracefully (shows error to user, doesn't crash)

Restart LLM gateway and verify recovery.

---

## Step 6: Test with MCP Tools (Optional)

If you have MCP servers running:

### 6.1 Configure MCP Server

Update `mcp-servers.edn`:
```clojure
{:servers
  {:stripe
   {:url "http://localhost:3001/mcp"
    :tools ["retrieve_customer" "list_charges"]}}
 
 :llm
 {:fallbacks [...]}}
```

Restart mcp-injector.

### 6.2 Test Tool Execution

Ask OpenClaw something that would use the tool:
```
You: Look up customer cus_123 in Stripe
```

**Expected flow:**
1. OpenClaw → mcp-injector (with tool directory injected)
2. mcp-injector → LLM gateway
3. LLM gateway → LLM
4. LLM returns tool_call for `stripe.retrieve_customer`
5. mcp-injector executes tool against MCP server
6. mcp-injector sends result back to LLM gateway
7. LLM gateway → LLM with tool result
8. LLM returns final response
9. mcp-injector → OpenClaw (SSE format)

**Check:**
- MCP server received the tool call
- Final response includes tool result

---

## Step 7: Test Non-MCP Tool Pass-Through

If OpenClaw has its own tools (not MCP):

Ask something that triggers an OpenClaw tool:
```
You: Set a reminder for tomorrow
```

**Expected:**
- mcp-injector receives tool_call for `openclaw.reminder` (or similar)
- Since it's not in MCP config, passes through to OpenClaw in SSE
- OpenClaw handles its own tool

---

## Troubleshooting

### Issue: OpenClaw can't connect to mcp-injector

**Check:**
```bash
curl http://localhost:8080/health
```

**If fails:**
- Verify mcp-injector is running
- Check port (default 8080)
- Check firewall/network

### Issue: mcp-injector can't connect to LLM gateway

**Check logs:**
```
Error handling request: LLM gateway error: 503
```

**Solutions:**
- Verify LLM gateway URL in config
- Check LLM gateway is running: `curl http://localhost:8081/health`
- Check network connectivity

### Issue: Fallbacks not being sent

**Check:**
- Verify `mcp-servers.edn` has `:llm {:fallbacks [...]}`
- Check mcp-injector loaded config (look for logs on startup)
- Verify EDN syntax is valid

### Issue: Stream conversion not working

**Symptoms:** OpenClaw receives garbled response or hangs

**Check:**
- Verify LLM gateway is returning JSON (not SSE)
- Check mcp-injector logs for parsing errors
- Test directly: `curl -N http://localhost:8080/v1/chat/completions -d '{"model":"test","messages":[{"role":"user","content":"hi"}],"stream":true}'`

### Issue: Tool calls not executing

**Check:**
- MCP server URL is correct in config
- MCP server is running and accessible
- Tool name format is `server.tool` (e.g., `stripe.retrieve_customer`)
- Check mcp-injector logs for "MCP server not found" errors

---

## Performance Notes

- **Timeout:** 30 seconds (configurable in code if needed)
- **Retries:** Currently none (LLM gateway handles fallbacks)
- **Connection pooling:** Not implemented (may be added later)

If you see timeouts under load, may need to:
1. Increase timeout
2. Add connection pooling
3. Implement retries

---

## Rollback Plan

If things go wrong:

1. Stop mcp-injector: `Ctrl+C`
2. Revert OpenClaw config to point directly at LLM gateway
3. Restart OpenClaw

---

## Success Criteria

✅ OpenClaw works normally (users can chat)  
✅ LLM gateway receives fallbacks array  
✅ LLM gateway receives stream=false  
✅ OpenClaw receives SSE responses  
✅ Rate limits handled gracefully  
✅ LLM gateway outages handled gracefully  
✅ (Optional) MCP tools execute correctly  
✅ (Optional) Non-MCP tools pass through to OpenClaw  

---

**Questions? Issues?** Check the logs first - they're your best friend for debugging.
