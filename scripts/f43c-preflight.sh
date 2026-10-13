#!/usr/bin/env bash
# Safe, read-only preflight. No token output, no model execution, no project writes.
set -euo pipefail
command -v curl >/dev/null || { echo 'Missing curl' >&2; exit 2; }
command -v java >/dev/null || { echo 'Missing Java' >&2; exit 2; }
[ -x ./gradlew ] || { echo 'Run from repository root' >&2; exit 2; }
OLLAMA_URL="${AIES_MODEL_BASE_URL:-http://127.0.0.1:11434}"
case "$OLLAMA_URL" in http://127.0.0.1:*|http://localhost:*) ;; *) echo 'Only local Ollama allowed for this preflight' >&2; exit 2;; esac
MODEL="${AIES_MODEL_NAME:-qwen2.5-coder:7b}"
case "$MODEL" in *[!a-zA-Z0-9._:/-]*|'') echo 'Unsafe model name' >&2; exit 2;; esac
printf 'Java: '; java -version 2>&1 | head -n 1
printf 'Ollama health: '
curl --silent --show-error --fail --max-time 5 "$OLLAMA_URL/api/tags" > /dev/null && echo OK
printf 'Model requested: %s\n' "$MODEL"
printf 'Workspace token configured: '
[ -n "${AIES_WORKSPACE_TOKEN:-}" ] && echo YES || echo NO
printf 'Note: preflight is NOT an E2E or model-version verification.\n'
