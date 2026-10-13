#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
BASE="${AIES_MODEL_BASE_URL:-http://127.0.0.1:11434}"
MODEL="${AIES_MODEL_NAME:-qwen3:4b}"
case "$BASE" in http://127.0.0.1:*|http://localhost:*|http://127.0.0.1|http://localhost) ;; *) echo 'Only loopback Ollama is permitted' >&2; exit 2;; esac
command -v curl >/dev/null || { echo 'curl required' >&2; exit 2; }
curl --fail --silent --show-error --max-time 5 "$BASE/api/tags" >/dev/null || { echo 'Ollama not reachable' >&2; exit 2; }
echo "Local Ollama reachable; requested model: $MODEL"
AIES_F43G2_LIVE=true ./gradlew :apps:control-plane:test --tests '*F43G2OllamaAuditLiveTest' --rerun-tasks --no-daemon --console=plain
