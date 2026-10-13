#!/usr/bin/env bash
set -euo pipefail
command -v curl >/dev/null || { echo 'curl required' >&2; exit 1; }
command -v jq >/dev/null || { echo 'jq required' >&2; exit 1; }
base="${AIES_MODEL_BASE_URL:-http://127.0.0.1:11434}"
model="${AIES_MODEL_NAME:-qwen3:4b}"
case "$base" in http://127.0.0.1:*|http://localhost:*|http://127.0.0.1|http://localhost) ;; *) echo 'Only loopback Ollama allowed' >&2; exit 1;; esac
curl --fail --silent --show-error --max-time 5 "$base/api/tags" | jq -e --arg model "$model" '.models | any(.name == $model or .model == $model)' >/dev/null || { echo "Local model unavailable: $model" >&2; exit 1; }
echo "Local Ollama model available: $model"
AIES_F43E_LIVE=true ./gradlew :apps:control-plane:test --tests '*F43EOllamaLiveTest' --rerun-tasks --no-daemon --console=plain
