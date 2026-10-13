#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/f43g10e-http.sh"
: "${AIES_INTAKE_FILE:?Real intake JSON required}"
[[ -f "$AIES_INTAKE_FILE" && ! -L "$AIES_INTAKE_FILE" ]] || { echo 'Intake must be regular, non-symlink file' >&2; exit 2; }
[[ "$(wc -c < "$AIES_INTAKE_FILE")" -le 262144 ]] || exit 2
command -v sha256sum >/dev/null || exit 2
MODE="${1:-}"
[[ "$MODE" == create || "$MODE" == inspect || "$MODE" == resume ]] || { echo 'Usage: script create|inspect|resume' >&2; exit 2; }
MAX_POLL="${AIES_MAX_POLL:-60}"; POLL_SECONDS="${AIES_POLL_SECONDS:-2}"
[[ "$MAX_POLL" =~ ^[1-9][0-9]{0,2}$ ]] && (( MAX_POLL <= 120 )) || exit 2
[[ "$POLL_SECONDS" =~ ^[1-9][0-9]?$ ]] && (( POLL_SECONDS <= 30 )) || exit 2
jq -e '.schemaVersion=="1.0" and (.workflowId|type=="string" and test("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")) and (.requirements|type=="array" and length>0) and (.sources|type=="array")' "$AIES_INTAKE_FILE" >/dev/null || exit 2
ID="$(jq -r '.workflowId' "$AIES_INTAKE_FILE")"
HASH="$(sha256sum "$AIES_INTAKE_FILE" | cut -d' ' -f1)"
DIR="$(mktemp -d)"; trap 'rm -rf -- "$DIR"' EXIT
ROOT="/api/v1/planning/workflows/$ID"
echo "Workflow=$ID intake_sha256=$HASH mode=$MODE"
aies_http GET "$ROOT" "$DIR/existing.json"
if [[ "$MODE" == create ]]; then
  # 404 is ambiguous under fail-closed authorization: POST is single attempt only.
  [[ "$AIES_HTTP_STATUS" == 404 ]] || { echo "Workflow GET HTTP $AIES_HTTP_STATUS; refusing duplicate/ambiguous create" >&2; exit 3; }
  aies_http POST '/api/v1/planning/workflows' "$DIR/created.json" "$AIES_INTAKE_FILE"
  [[ "$AIES_HTTP_STATUS" == 201 ]] || { echo "Create HTTP $AIES_HTTP_STATUS; inspect before retry" >&2; exit 4; }
  [[ "$(jq -r '.workflowId' "$DIR/created.json")" == "$ID" ]] || exit 4
else
  [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "Existing workflow unavailable HTTP $AIES_HTTP_STATUS" >&2; exit 4; }
  # Compare exact canonical intake to durable snapshot; never trust ID alone.
  jq -S -c . "$AIES_INTAKE_FILE" > "$DIR/intake.normalized"
  jq -S -c '.intake' "$DIR/existing.json" > "$DIR/persisted.normalized"
  cmp -s "$DIR/intake.normalized" "$DIR/persisted.normalized" || { echo 'Durable intake mismatch; refusing resume' >&2; exit 5; }
fi
STATUS=''
for ((i=1;i<=MAX_POLL;i++)); do
  aies_http GET "$ROOT" "$DIR/plan.json"
  [[ "$AIES_HTTP_STATUS" == 200 ]] || exit 4
  [[ "$(jq -r '.workflowId' "$DIR/plan.json")" == "$ID" ]] || exit 4
  STATUS="$(jq -r '.status' "$DIR/plan.json")"
  case "$STATUS" in
    READY_FOR_ASSIGNMENT|BLOCKED|REVISION_REQUIRED) break ;;
    RECEIVED|AUDIT_REQUIRED|AWAITING_AUDIT) [[ "$MODE" != inspect ]] && sleep "$POLL_SECONDS" || break ;;
    *) echo "Unexpected planning status $STATUS" >&2; exit 4 ;;
  esac
done
echo "Planning=$STATUS artifact=$(jq -r '.artifactRecordId // "none"' "$DIR/plan.json") audit=$(jq -r '.auditRecordId // "none"' "$DIR/plan.json")"
[[ "$STATUS" == READY_FOR_ASSIGNMENT ]] || { echo 'Not approved; no execution or import' >&2; exit 3; }
jq -e '.plan!=null and (.auditRecordId|type=="string" and length>0) and (.artifactRecordId|type=="string" and length>0)' "$DIR/plan.json" >/dev/null || exit 4
[[ "$MODE" == resume ]] || { echo "Review durable plan before explicit resume: GET $ROOT"; exit 0; }
[[ "${AIES_OPERATOR_APPROVE_WORKFLOW:-}" == "$ID" ]] || { echo 'Explicit approval acknowledgement required for resume' >&2; exit 3; }
# Before POST, inspect durable execution state; 404 is not evidence of authorization.
aies_http GET "/api/v1/execution/workflows/$ID/acceptance" "$DIR/existing-execution.json"
[[ "$AIES_HTTP_STATUS" == 404 ]] || { echo "Execution state HTTP $AIES_HTTP_STATUS; inspect manually before import" >&2; exit 3; }
aies_http POST "/api/v1/execution/workflows/$ID/import" "$DIR/import.json"
[[ "$AIES_HTTP_STATUS" == 200 || "$AIES_HTTP_STATUS" == 201 ]] || { echo "Import HTTP $AIES_HTTP_STATUS; reconcile before retry" >&2; exit 4; }
[[ "$(jq -r '.workflowId' "$DIR/import.json")" == "$ID" ]] || exit 4
echo 'Imported; no automatic execution. Run G5 separately after inspection.'
