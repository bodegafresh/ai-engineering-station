#!/usr/bin/env bash
set -euo pipefail
for bin in curl jq; do command -v "$bin" >/dev/null || { echo "Missing $bin" >&2; exit 2; }; done
: "${AIES_WORKFLOW_ID:?Set existing approved/imported workflow ID}"
: "${AIES_WORKSPACE_TOKEN:?Set operator token (never print it)}"
BASE="${AIES_BASE_URL:-http://127.0.0.1:8080}"
[[ "$BASE" == "http://127.0.0.1:"* || "$BASE" == "http://localhost:"* ]] || { echo 'Only loopback HTTP allowed' >&2; exit 2; }
[[ "$AIES_WORKFLOW_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}$ ]] || { echo 'Invalid workflow ID' >&2; exit 2; }
DIR="$(mktemp -d)"; chmod 700 "$DIR"; trap 'rm -rf "$DIR"' EXIT
URL="$BASE/api/v1/execution/workflows/$AIES_WORKFLOW_ID"
request() {
  local method="$1" path="$2" file="$3" code
  code="$(curl --silent --show-error --max-time 210 --output "$file" --write-out '%{http_code}' --request "$method" --header "X-AIES-Workspace-Token: $AIES_WORKSPACE_TOKEN" "$URL$path")"
  [[ "$code" == 200 ]] || { echo "Request $method $path returned HTTP $code; stop and inspect durable state" >&2; exit 1; }
  jq -e 'type=="object"' "$file" >/dev/null
}
request GET /tasks "$DIR/tasks.json"
request GET /acceptance "$DIR/acceptance.json"
echo 'Initial acceptance:'
jq -r '.acceptanceStatus' "$DIR/acceptance.json"
# A single operator command per loop: no automatic approval, no fabricated audit.
for ((i=1;i<=${AIES_MAX_STEPS:-30};i++)); do
  request POST /operator/run-one "$DIR/run.json"
  if [[ "$(jq -r '.claimed' "$DIR/run.json")" != true ]]; then
    echo 'No claimable task; stopping (may be incomplete due to pending/rejected audit).'
    break
  fi
  task="$(jq -r '.taskId // empty' "$DIR/run.json")"
  [[ "$task" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,119}$ ]] || { echo 'Invalid task ID returned' >&2; exit 1; }
  status="$(jq -r '.status // empty' "$DIR/run.json")"
  echo "Attempt $i task=$task status=$status"
  if [[ "$status" == EVIDENCE_PENDING_VERIFICATION ]]; then
    request POST "/operator/tasks/$task/review" "$DIR/review.json"
    echo "Audit decision=$(jq -r '.decision // "UNKNOWN"' "$DIR/review.json") status=$(jq -r '.status // "UNKNOWN"' "$DIR/review.json")"
  elif [[ "$status" != RETRY_PENDING && "$status" != DEAD && "$status" != RECONCILIATION_REQUIRED ]]; then
    echo 'Unexpected worker status; stopping' >&2; exit 1
  fi
  [[ "$status" != DEAD && "$status" != RECONCILIATION_REQUIRED ]] || break
done
request GET /acceptance "$DIR/final.json"
echo 'Final acceptance:'
jq '{workflowId,acceptanceStatus,tasks:[.tasks[]|{taskId,status,artifactId,artifactSha256,auditId,auditDecision}],limitations}' "$DIR/final.json"
[[ "$(jq -r '.acceptanceStatus' "$DIR/final.json")" == VERIFIED ]] || {
  echo 'INCOMPLETE: no assertion of full E2E acceptance' >&2; exit 3;
}
