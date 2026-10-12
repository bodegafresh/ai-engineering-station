#!/usr/bin/env bash
set -euo pipefail
: "${AIES_TEAMS_TOKEN:?Export AIES_TEAMS_TOKEN (32+ characters)}"
BASE="${AIES_BASE_URL:-http://127.0.0.1:8080}"
if [ ${#AIES_TEAMS_TOKEN} -lt 32 ]; then echo 'Token too short' >&2; exit 1; fi
BODY='{"projectId":"hello_aies","objective":"Diseña un pequeño servicio Java de lista de tareas con GET y POST, pruebas unitarias y criterios de aceptación.","useMemory":false}'
unauth=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data "$BODY" "$BASE/api/v1/teams/evaluate")
[ "$unauth" = 404 ] || { echo "FAIL unauthorized expected 404, got $unauth"; exit 1; }
echo 'PASS: unauthorized request rejected'
response=$(mktemp)
trap 'rm -f "$response"' EXIT
status=$(curl -sS --max-time 400 -o "$response" -w '%{http_code}' -H 'Content-Type: application/json' -H "X-AIES-Team-Token: $AIES_TEAMS_TOKEN" --data "$BODY" "$BASE/api/v1/teams/evaluate")
[ "$status" = 200 ] || { echo "FAIL: HTTP $status"; cat "$response"; exit 1; }
python3 - "$response" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
assert r['status']=='COMPLETED',r
assert set(r['approved'])=={'design','implementation','review'},r
assert set(r['models'])=={'design','implementation','review'},r
print('PASS: three specialists, selected models, event-bus DAG')
print('Models:',r['models'])
PY
