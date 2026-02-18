#!/usr/bin/env bash

# Simple curl test for mcp-injector shim
# Usage: ./test-curl.sh [shim-url]

SHIM_URL="${1:-http://localhost:8088}"

echo "Testing shim at: $SHIM_URL"
echo "=========================="

# Test 1: Simple request (no streaming)
echo -e "\n--- Test 1: Simple Request ---"
RESPONSE=$(curl -s -X POST "$SHIM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "zen/kimi-k2.5-free",
    "messages": [{"role": "user", "content": "Say hi"}],
    "stream": false
  }')

echo "Response: $RESPONSE"
CONTENT=$(echo "$RESPONSE" | jq -r '.choices[0].message.content')
if [ "$CONTENT" = "null" ] || [ -z "$CONTENT" ]; then
  echo "❌ FAILED: No content returned"
  echo "Error details: $(echo "$RESPONSE" | jq -r '.error // "none"')"
else
  echo "✅ SUCCESS: $CONTENT"
fi

# Test 2: With fallbacks
echo -e "\n--- Test 2: With Fallbacks ---"
RESPONSE=$(curl -s -X POST "$SHIM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "zen/kimi-k2.5-free",
    "messages": [{"role": "user", "content": "2+2=?"}],
    "stream": false,
    "fallbacks": ["nvidia/moonshotai/kimi-k2.5", "openrouter/moonshotai/kimi-k2.5"]
  }')

echo "Response: $RESPONSE"
CONTENT=$(echo "$RESPONSE" | jq -r '.choices[0].message.content')
if [ "$CONTENT" = "null" ] || [ -z "$CONTENT" ]; then
  echo "❌ FAILED: No content returned"
  echo "Error details: $(echo "$RESPONSE" | jq -r '.error // "none"')"
else
  echo "✅ SUCCESS: $CONTENT"
fi

# Test 3: Streaming (check for actual content)
echo -e "\n--- Test 3: Streaming ---"
RESPONSE=$(curl -s -X POST "$SHIM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "zen/kimi-k2.5-free",
    "messages": [{"role": "user", "content": "hi"}],
    "stream": true
  }')

echo "First 3 lines:"
echo "$RESPONSE" | head -3
if echo "$RESPONSE" | grep -q "data:"; then
  echo "✅ Got SSE response"
else
  echo "❌ No SSE data"
fi

echo -e "\n=========================="
echo "Done!"
