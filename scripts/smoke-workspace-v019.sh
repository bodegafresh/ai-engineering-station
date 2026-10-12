#!/usr/bin/env bash
set -euo pipefail
: "${AIES_TEAMS_TOKEN:?Set AIES_TEAMS_TOKEN (32+ characters)}"
BASE="${AIES_BASE_URL:-http://127.0.0.1:8080}"
PROJECT="${AIES_SMOKE_PROJECT:-hello_aies}"
BODY=$(python3 - "$PROJECT" <<'PY'
import json,sys
print(json.dumps({"projectId":sys.argv[1],"objective":"Evalúa la arquitectura, riesgos y pruebas del código Java del repositorio, citando archivos realmente presentes.","useMemory":False,"useWorkspace":True}))
PY
)
unauth=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' --data "$BODY" "$BASE/api/v1/teams/evaluate")
[ "$unauth" = 404 ] || { echo "FAIL unauthorized HTTP $unauth" >&2; exit 1; }
echo 'PASS unauthorized rejected'
response=$(mktemp);trap 'rm -f "$response"' EXIT
status=$(curl -sS --max-time 420 -o "$response" -w '%{http_code}' -H 'Content-Type: application/json' -H "X-AIES-Team-Token: $AIES_TEAMS_TOKEN" --data "$BODY" "$BASE/api/v1/teams/evaluate")
[ "$status" = 200 ] || { echo "FAIL HTTP $status" >&2; cat "$response"; exit 1; }
python3 - "$response" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]));print('Status:',r.get('status'));print('Files:',r.get('workspaceFiles'));print('Models:',r.get('models'))
assert r.get('status')=='COMPLETED',r
assert r.get('workspaceUsed') is True,r
assert r.get('workspaceFiles'),r
assert set(r.get('approved',{}))=={'design','implementation','review'},r
print('PASS three-agent read-only workspace evaluation')
PY
