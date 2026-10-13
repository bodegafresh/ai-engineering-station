#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/f43g10e-http.sh"
: "${AIES_WORKFLOW_ID:?Existing approved/imported workflow ID required}"
aies_id "$AIES_WORKFLOW_ID" || { echo 'Invalid workflow ID' >&2; exit 2; }
STEPS="${AIES_MAX_STEPS:-30}"
[[ "$STEPS" =~ ^[1-9][0-9]?$ ]] && (( STEPS <= 90 )) || { echo 'Invalid step bound' >&2; exit 2; }
DIR="$(mktemp -d)"; trap 'rm -rf -- "$DIR"' EXIT
ROOT="/api/v1/execution/workflows/$AIES_WORKFLOW_ID"
read_endpoint() {
  aies_http GET "$ROOT/$1" "$DIR/$1.json"
  [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "GET $1 HTTP $AIES_HTTP_STATUS" >&2; exit 4; }
  jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId==$id' "$DIR/$1.json" >/dev/null || { echo "Invalid $1 correlation" >&2; exit 4; }
}
read_endpoint acceptance
jq -e '.acceptanceStatus=="VERIFIED" or .acceptanceStatus=="INCOMPLETE"' "$DIR/acceptance.json" >/dev/null || exit 4
for ((i=1;i<=STEPS;i++)); do
  read_endpoint acceptance
  if [[ "$(jq -r '.acceptanceStatus' "$DIR/acceptance.json")" == VERIFIED ]]; then
    echo "CANONICAL_VERIFIED workflow=$AIES_WORKFLOW_ID"; exit 0
  fi
  aies_http GET "$ROOT/tasks" "$DIR/tasks.json"
  [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "Tasks HTTP $AIES_HTTP_STATUS" >&2; exit 4; }
  jq -e 'type=="array"' "$DIR/tasks.json" >/dev/null || { echo "Invalid tasks contract" >&2; exit 4; }
  # No auto-repeat if any task has pending evidence, dead/reconciliation or active attempt.
  if jq -e 'any(.[]; .status=="EVIDENCE_PENDING_VERIFICATION" or .status=="RECONCILIATION_REQUIRED" or .status=="DEAD" or .status=="RUNNING")' "$DIR/tasks.json" >/dev/null 2>&1; then
    echo 'Pending/ambiguous task: inspect and review manually before invoking worker' >&2; exit 3
  fi
  aies_http POST "$ROOT/operator/run-one" "$DIR/run.json"
  [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "run-one HTTP $AIES_HTTP_STATUS; reconcile before retry" >&2; exit 4; }
  if [[ "$(jq -r '.claimed // false' "$DIR/run.json")" != true ]]; then
    echo 'No claimable task; no approval assumed' >&2; exit 3
  fi
  task="$(jq -r '.taskId // empty' "$DIR/run.json")"
  aies_id "$task" || { echo 'Invalid returned task ID' >&2; exit 4; }
  status="$(jq -r '.status // empty' "$DIR/run.json")"
  echo "Attempt $i task=$task status=$status"
  case "$status" in
    EVIDENCE_PENDING_VERIFICATION)
      # Audit is a real production Event Bus action; no fabricated decision.
      aies_http POST "$ROOT/operator/tasks/$task/review" "$DIR/review.json"
      [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "Review HTTP $AIES_HTTP_STATUS; reconcile before retry" >&2; exit 4; }
      echo "Review decision=$(jq -r '.decision // "UNKNOWN"' "$DIR/review.json")"
      ;;
    RETRY_PENDING|DEAD|RECONCILIATION_REQUIRED)
      echo 'Non-success attempt: stop; operator investigation required' >&2; exit 3 ;;
    *) echo 'Unknown worker status; stop' >&2; exit 4 ;;
  esac
done
read_endpoint acceptance
[[ "$(jq -r '.acceptanceStatus' "$DIR/acceptance.json")" == VERIFIED ]] || { echo 'INCOMPLETE: no canonical certification' >&2; exit 3; }
echo "CANONICAL_VERIFIED workflow=$AIES_WORKFLOW_ID"
