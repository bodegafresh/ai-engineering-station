#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${AIES_TOOLS_TOKEN:?Set AIES_TOOLS_TOKEN first}"
BASE="${AIES_GATEWAY_URL:-http://127.0.0.1:8080}"
echo '[1/5] Reject without token'
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/tools/execute" -H 'Content-Type: application/json' -d '{"tool":"diagram.render","approved":true,"source":"examples/architecture.puml","output":".aies/artifacts/test.svg"}')
[ "$code" = 401 ] || { echo "Expected 401, got $code"; exit 1; }
echo '[2/5] Reject without approval'
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/tools/execute" -H 'Content-Type: application/json' -H "X-AIES-Tool-Token: $AIES_TOOLS_TOKEN" -d '{"tool":"diagram.render","approved":false,"source":"examples/architecture.puml","output":".aies/artifacts/test.svg"}')
[ "$code" = 403 ] || { echo "Expected 403, got $code"; exit 1; }
echo '[3/5] Reject arbitrary shell'
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/tools/execute" -H 'Content-Type: application/json' -H "X-AIES-Tool-Token: $AIES_TOOLS_TOKEN" -d '{"tool":"shell.exec","approved":true,"output":".aies/artifacts/test.svg"}')
[ "$code" = 400 ] || { echo "Expected 400, got $code"; exit 1; }
echo '[4/5] Diagram render'
curl -fsS -X POST "$BASE/api/v1/tools/execute" -H 'Content-Type: application/json' -H "X-AIES-Tool-Token: $AIES_TOOLS_TOKEN" -d '{"tool":"diagram.render","approved":true,"source":"examples/architecture.puml","output":".aies/artifacts/v05-smoke.svg"}' | jq -e '.ok == true'
test -s .aies/artifacts/v05-smoke.svg
echo '[5/5] Browser inspect (requires local server 8765)'
curl -fsS -X POST "$BASE/api/v1/tools/execute" -H 'Content-Type: application/json' -H "X-AIES-Tool-Token: $AIES_TOOLS_TOKEN" -d '{"tool":"browser.inspect","approved":true,"url":"http://127.0.0.1:8765/","output":".aies/artifacts/v05-smoke.png"}' | jq -e '.ok == true and .status == 200'
test -s .aies/artifacts/v05-smoke.png
echo 'PASS: v0.5 tool gateway smoke'
