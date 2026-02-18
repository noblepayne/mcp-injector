#!/bin/bash
# Test each provider model directly against the LLM gateway

LLM_URL="http://localhost:8080"

echo "=========================================="
echo "Testing models directly against LLM gateway"
echo "=========================================="
echo ""

echo "--- Test zen/kimi-k2.5-free ---"
curl -s -X POST "$LLM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"zen/kimi-k2.5-free","messages":[{"role":"user","content":"Hi"}],"max_tokens":10}'
echo ""
echo ""

echo "--- Test nvidia/moonshotai/kimi-k2.5 ---"
curl -s -X POST "$LLM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"nvidia/moonshotai/kimi-k2.5","messages":[{"role":"user","content":"Hi"}],"max_tokens":10}'
echo ""
echo ""

echo "--- Test openrouter/moonshotai/kimi-2.5 ---"
curl -s -X POST "$LLM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"openrouter/moonshotai/kimi-2.5","messages":[{"role":"user","content":"Hi"}],"max_tokens":10}'
echo ""
echo ""

echo "--- Test openrouter/ai/moonshot-kimi-1.5-pro ---"
curl -s -X POST "$LLM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"openrouter/ai/moonshot-kimi-1.5-pro","messages":[{"role":"user","content":"Hi"}],"max_tokens":10}'
echo ""
echo ""

echo "--- Test moonshotai/kimi-k2.5 (direct) ---"
curl -s -X POST "$LLM_URL/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"model":"moonshotai/kimi-k2.5","messages":[{"role":"user","content":"Hi"}],"max_tokens":10}'
echo ""
echo ""

echo "=========================================="
echo "Done!"
echo "=========================================="
