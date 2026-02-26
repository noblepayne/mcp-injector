# MCP Server Configuration Review & Recommendations

**Date:** 2026-02-25  
**Author:** J.O.E. (AI Agent)  
**Purpose:** Internal report for lead dev agent on mcp-injector configuration improvements

---

## Executive Summary

This report analyzes the current mcp-injector configuration against industry best practices from OpenCode and Claude Code, and proposes improvements for consideration.

**Current Stats:**
- Model: `brain` (virtual chain with fallback)
- Total Requests: 641
- Total Tokens: 33.4M input / 114K output
- Port: 8089

---

## Current Configuration Overview

```nix
services.mcp-injector = {
  enable = true;
  port = 8089;
  host = "127.0.0.1";
  llmUrl = "http://localhost:8080";
  
  mcpServers = {
    llm-gateway = {
      url = "http://localhost:8080";
      virtual-models = {
        brain = {
          chain = [
            # 12 free models
            "zen/minimax-m2.5-free"
            "zen/kimi-k2.5-free"
            # ... 10 more
            # 2 paid fallbacks
            "openrouter/minimax/minimax-m2.5"
            "openrouter/moonshotai/kimi-k2.5"
          ];
          cooldown-minutes = 5;
          retry-on = [429];
        };
      };
    };
    servers = {
      auphonic = { url = "http://localhost:3000/mcp"; };
      nextcloud = { url = "http://localhost:8000/mcp"; };
      freshrss = { url = "http://localhost:3005/mcp"; };
      art19 = { url = "http://localhost:3007/mcp"; };
    };
  };
};
```

---

## Strengths of Current Setup

1. **Virtual Model Chains with Fallback** — Intelligent retry logic when free models fail
2. **Cooldown + 429 Handling** — Prevents hammering rate-limited APIs
3. **Multiple Provider Diversity** — Mix of Zen, OpenRouter, NVIDIA endpoints
4. **Paid Fallback** — Ensures availability when free tier exhausts
5. **Multiple MCP Servers** — Auphonic, Nextcloud, FreshRSS, Art19 for podcast production

---

## Recommendations

### 1. Per-Server Timeout Configuration

**Problem:** OpenCode defaults to 5s timeout per MCP server. Our config has no timeout specified.

**Recommendation:** Add explicit timeouts per MCP server based on expected response times:

```nix
servers = {
  art19 = {
    url = "http://localhost:3007/mcp";
    timeout = 30000;  # 30s for podcast metadata
  };
  freshrss = {
    url = "http://localhost:3005/mcp";
    timeout = 10000;  # 10s for RSS feeds
  };
};
```

---

### 2. Tool Scoping (Per-Model MCP Selection)

**Problem:** All MCPs are available to all model requests. Some MCPs (Art19) add significant token overhead.

**Recommendation:** Add ability to scope MCPs to specific model chains:

```nix
virtual-models = {
  brain = {
    chain = [...];
    mcp-servers = ["auphonic" "nextcloud" "freshrss"];  # Exclude art19 by default
  };
  brain-art19 = {
    chain = [...];
    mcp-servers = ["auphonic" "nextcloud" "freshrss" "art19"];  # Include art19
  };
};
```

---

### 3. Enhanced Authentication Headers

**Problem:** Nextcloud MCP currently uses env file only. Claude Code supports per-server headers.

**Recommendation:** Support header-based auth:

```nix
nextcloud = {
  url = "http://localhost:8000/mcp";
  headers = {
    Authorization = "Bearer ${NEXTCLOUD_API_TOKEN}";
  };
};
```

---

### 4. Dynamic Tool Reload Support

**Problem:** MCP servers can send `list_changed` notifications to update their capabilities. Claude Code supports this; our setup doesn't reload.

**Recommendation:** Add support for dynamic tool updates when MCP servers change their available tools/prompts/resources.

---

### 5. Config Structure Clarification

**Problem:** The dual purpose of mcp-injector (virtual model routing + MCP tool hosting) creates awkward nesting with `llm-gateway` vs `servers`.

**Recommendation:** Consider restructured config:

```nix
services.mcp-injector = {
  # Virtual model routing config
  virtual-models = { ... };
  
  # Actual MCP servers for tools
  mcp-servers = { ... };
  
  # Or split into separate services:
  # services.mcp-injector-virtual-models
  # services.mcp-injector-servers
};
```

---

### 6. MCP Server Health Monitoring

**Problem:** No built-in health checks for MCP servers.

**Recommendation:** Add health endpoint that checks each MCP server:

```nix
services.mcp-injector = {
  # Existing config...
  health-check = {
    enabled = true;
    interval = 60;  # seconds
    timeout = 5000;
  };
};
```

---

### 7. Request Logging Enhancements

**Problem:** Current stats only show token counts, not which MCPs were used per request.

**Recommendation:** Enhanced stats tracking:

```json
{
  "model": "brain",
  "requests": 641,
  "mcp-usage": {
    "auphonic": 45,
    "art19": 12,
    "nextcloud": 8,
    "freshrss": 3
  },
  "fallback-count": 23,
  "avg-latency-ms": 1200
}
```

---

## Priority Recommendations

| Priority | Item | Effort | Impact |
|----------|------|--------|--------|
| High | Per-server timeouts | Low | Reliability |
| High | Config structure cleanup | Medium | Maintainability |
| Medium | Tool scoping per model | Medium | Token savings |
| Medium | Enhanced auth headers | Low | Security |
| Low | Dynamic tool reload | High | Flexibility |
| Low | Health monitoring | Medium | Observability |

---

## Conclusion

The current mcp-injector configuration is solid and well-thought-out. The virtual model chain with fallback is a smart approach. The main areas for improvement are:

1. **Explicit timeouts** for MCP servers
2. **Cleaner config structure** that separates virtual model routing from MCP tool hosting
3. **Per-model MCP scoping** to reduce unnecessary token overhead

These changes would improve reliability, maintainability, and cost efficiency without major architectural changes.

---

*Report generated by J.O.E. - 2026-02-25*
