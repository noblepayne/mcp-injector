#!/bin/bash
# Test virtual model 'brain' with streaming
# Simulates OpenClaw calling the virtual model endpoint

set -e

echo "=========================================="
echo "Testing Virtual Model: brain"
echo "=========================================="
echo ""
echo "Sending streaming request to http://localhost:8088/v1/chat/completions"
echo "Model: brain (virtual)"
echo "Expected chain: zen/kimi-k2.5-free -> nvidia/moonshotai/kimi-k2.5 -> openrouter/moonshotai/kimi-k2.5"
echo ""

# Test 1: Simple streaming request with brain virtual model
echo "--- Test 1: Virtual model with streaming ---"
echo "Request:"
echo '{"model":"brain","messages":[{"role":"user","content":"Hi"}],"stream":true}'
echo ""
echo "Response (SSE format):"
echo ""

# Use -N to disable buffering for streaming, -s for silent
curl -s -N -X POST http://localhost:8088/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "brain",
    "messages": [{"role": "user", "content": "Hi"}],
    "stream": true,
    "max_tokens": 200
  }' | while IFS= read -r line; do
    echo "$line"
done

echo ""
echo ""

# Test 2: Non-streaming with brain virtual model
echo "--- Test 2: Virtual model without streaming ---"
echo "Request:"
echo '{"model":"brain","messages":[{"role":"user","content":"Say hello"}],"stream":false}'
echo ""
echo "Response (JSON format):"
echo ""

response=$(curl -s -X POST http://localhost:8088/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "brain",
    "messages": [{"role": "user", "content": "Say hello"}],
    "stream": false,
    "max_tokens": 200
  }')

echo "$response" | jq . 2>/dev/null || echo "$response"

echo ""
echo ""

# Test 3: Check which provider actually responded
echo "--- Test 3: Check provider used ---"
echo "Looking at logs to see which provider in the chain succeeded..."
echo "(Check mcp-injector logs for 'Virtual model succeeded' message)"
echo ""

echo "=========================================="
echo "Test complete!"
echo "=========================================="
echo ""
echo "Expected behavior:"
echo "1. First provider (zen/kimi-k2.5-free) is tried"
echo "2. If it fails with 429/500/502/503, falls back to nvidia"
echo "3. If nvidia fails, falls back to openrouter"
echo "4. Successful provider is cached for cooldown period"
echo ""
