#!/usr/bin/env bash

# Test script for mcp-injector shim
# Tests the shim with simple requests to verify it works correctly

set -e

SHIM_URL="${SHIM_URL:-http://localhost:8088}"

echo "Testing mcp-injector shim at $SHIM_URL"
echo "=========================================="

# Test 1: Simple completion request
echo -e "\n1. Testing simple completion request..."
curl -s -X POST "$SHIM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "zen/kimi-k2.5-free",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": false,
    "max_tokens": 100
  }' | jq '.' || echo "Failed to parse response"

# Test 2: Streaming request
echo -e "\n2. Testing streaming request..."
curl -s -X POST "$SHIM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "zen/kimi-k2.5-free",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": true,
    "max_tokens": 50
  }' | head -5

# Test 3: With fallbacks
echo -e "\n3. Testing with fallbacks..."
curl -s -X POST "$SHIM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "zen/kimi-k2.5-free",
    "messages": [{"role": "user", "content": "What is 2+2?"}],
    "stream": false,
    "fallbacks": ["nvidia/moonshotai/kimi-k2.5", "openrouter/moonshotai/kimi-k2.5"],
    "max_tokens": 50
  }' | jq '.choices[0].message.content' || echo "Failed"

echo -e "\n=========================================="
echo "Test complete!"
