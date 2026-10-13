#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
for bin in curl jq sha256sum; do command -v "$bin" >/dev/null || { echo "Missing $bin" >&2; exit 2; }; done
: "${AIES_INTAKE_FILE:?Provide a real intake JSON file}"
: "${AIES_WORKSPACE_CHANGES_TOKEN:?Provide centralized operator token; never print it}"
[[ -f "$AIES_INTAKE_FILE" && ! -L "$AIES_INTAKE_FILE" ]] || { echo 'Intake must be a regular, non-symlink file' >&2; exit 2; }
[[ $(wc -c < "$AIES_INTAKE_FILE") -le 262144 ]] || { echo 'Intake exceeds 256 KiB' >&2; exit 2; }
BASE="${AIES_BASE_URL:-http://127.0.0.1:8080}"
[[ "$BASE" =~ ^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]] || { echo 'Only explicit loopback HTTP host and port accepted' >&2; exit 2; }
[[ "$BASE" != *$'\n'* ]] || exit 2
[[ "${AIES_MAX_POLL:-60}" =~ ^[0-9]{1,3}$ ]] && (( AIES_MAX_POLL >= 1 && AIES_MAX_POLL <= 120 )) || { echo 'Invalid poll bound' >&2; exit 2; }
[[ "${AIES_POLL_SECONDS:-2}" =~ ^[0-9]{1,2}$ ]] && (( AIES_POLL_SECONDS >= 1 && AIES_POLL_SECONDS <= 30 )) || { echo 'Invalid poll interval' >&2; exit 2; }
jq -e '.schemaVersion=="1.0" and (.workflowId|type=="string" and test("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")) and (.requirements|type=="array" and length>0) and (.sources|type=="array")' "$AIES_INTAKE_FILE" >/dev/null || { echo 'Invalid intake envelope' >&2; exit 2; }
ID="$(jq -r '.workflowId' "$AIES_INTAKE_FILE")"
HASH="$(sha256sum "$AIES_INTAKE_FILE" | cut -d' ' -f1)"
DIR="$(mktemp -d)"; chmod 700 "$DIR"; trap 'rm -rf "$DIR"' EXIT
CODE=''
api() {
  local method="$1" path="$2" output="$3" body="${4:-}" url="$BASE$path"
  local -a args=(--silent --show-error --max-time 150 --connect-timeout 5 --noproxy '*' --request "$method" --output "$output" --write-out '%{http_code}' --header "X-AIES-Workspace-Token: $AIES_WORKSPACE_CHANGES_TOKEN")
  if [[ -n "$body" ]]; then args+=(--header 'Content-Type: application/json' --data-binary "@$body"); fi
  CODE="$(curl "${args[@]}" "$url")" || { echo "HTTP transport failed; inspect durable state before retry" >&2; exit 4; }
  jq -e 'type=="object"' "$output" >/dev/null 2>&1 || { echo "Unexpected response; HTTP $CODE" >&2; exit 4; }
}
echo "Workflow=$ID intake_sha256=$HASH"
api GET "/api/v1/planning/workflows/$ID" "$DIR/existing.json"
if [[ "$CODE" == 200 ]]; then
  echo 'Existing workflow detected; refusing to overwrite or replay intake.' >&2
  exit 3
fi
[[ "$CODE" == 404 ]] || { echo "Preflight HTTP $CODE; check feature flags and authorization" >&2; exit 4; }
api POST '/api/v1/planning/workflows' "$DIR/created.json" "$AIES_INTAKE_FILE"
[[ "$CODE" == 201 ]] || { echo "Create HTTP $CODE; reconcile before retry" >&2; exit 4; }
[[ "$(jq -r '.workflowId' "$DIR/created.json")" == "$ID" ]] || { echo 'Workflow correlation mismatch' >&2; exit 4; }
echo 'Intake persisted. Waiting for actual planning and audit.'
STATUS=''
for ((i=1;i<=AIES_MAX_POLL;i++)); do
  api GET "/api/v1/planning/workflows/$ID" "$DIR/plan.json"
  [[ "$CODE" == 200 ]] || { echo "Planning read HTTP $CODE" >&2; exit 4; }
  [[ "$(jq -r '.workflowId' "$DIR/plan.json")" == "$ID" ]] || exit 4
  STATUS="$(jq -r '.status' "$DIR/plan.json")"
  case "$STATUS" in
    READY_FOR_ASSIGNMENT|BLOCKED|REVISION_REQUIRED) break ;;
    RECEIVED|AUDIT_REQUIRED|AWAITING_AUDIT) sleep "${AIES_POLL_SECONDS:-2}" ;;
    *) echo "Unrecognized planning state: $STATUS" >&2; exit 4 ;;
  esac
done
echo "Planning status=$STATUS artifact=$(jq -r '.artifactRecordId // "none"' "$DIR/plan.json") audit=$(jq -r '.auditRecordId // "none"' "$DIR/plan.json")"
[[ "$STATUS" == READY_FOR_ASSIGNMENT ]] || { echo 'Plan not approved by actual audit; no import or execution.' >&2; exit 3; }
jq -e '.plan != null and (.auditRecordId|type=="string" and length>0) and (.artifactRecordId|type=="string" and length>0)' "$DIR/plan.json" >/dev/null || { echo 'Approved plan lacks durable evidence' >&2; exit 4; }
echo 'Review the persisted plan via authenticated GET before continuing.'
echo "GET $BASE/api/v1/planning/workflows/$ID"
if [[ "${AIES_OPERATOR_APPROVE_WORKFLOW:-}" != "$ID" ]]; then
  echo "Explicit human approval required: export AIES_OPERATOR_APPROVE_WORKFLOW=$ID" >&2
  echo 'No import or execution performed. Resume manually after reviewing the plan.' >&2
  exit 3
fi
api POST "/api/v1/execution/workflows/$ID/import" "$DIR/import.json"
[[ "$CODE" == 201 || "$CODE" == 200 ]] || { echo "Import HTTP $CODE; reconcile before retry" >&2; exit 4; }
[[ "$(jq -r '.workflowId' "$DIR/import.json")" == "$ID" ]] || { echo 'Import correlation mismatch' >&2; exit 4; }
echo 'Plan imported. Running existing operator execution smoke.'
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$HERE/f43g5-operator-e2e.sh" ]] || { echo 'Required G5 script not found' >&2; exit 4; }
AIES_WORKFLOW_ID="$ID" AIES_WORKSPACE_TOKEN="$AIES_WORKSPACE_CHANGES_TOKEN" bash "$HERE/f43g5-operator-e2e.sh"
api GET "/api/v1/execution/workflows/$ID/acceptance" "$DIR/acceptance.json"
[[ "$CODE" == 200 ]] || exit 4
jq -e --arg id "$ID" '.workflowId==$id and .acceptanceStatus=="VERIFIED" and (.tasks|length>0) and all(.tasks[]; .status=="COMPLETED" and .auditDecision=="APPROVED" and (.artifactSha256|type=="string" and length==64) and (.auditSha256|type=="string" and length==64))' "$DIR/acceptance.json" >/dev/null || { echo 'Canonical acceptance failed' >&2; exit 3; }
echo "CANONICAL_VERIFIED workflow=$ID intake_sha256=$HASH"
echo 'NOTE: This does not certify material bytes, crash recovery, event-loop latency, or external correctness.'
