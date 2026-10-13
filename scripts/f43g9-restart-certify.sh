#!/usr/bin/env bash
# F4.3-G10-F: authenticated, read-only restart snapshot comparison.
set -Eeuo pipefail
umask 077
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/f43g10e-http.sh"
: "${AIES_WORKFLOW_ID:?Existing imported workflow required}"
: "${AIES_CERT_DIR:?Persistent certification directory required}"
aies_id "$AIES_WORKFLOW_ID" || { echo 'Invalid workflow ID' >&2; exit 2; }
MODE="${1:-}"
[[ "$MODE" == before || "$MODE" == after ]] || { echo 'Usage: script before|after' >&2; exit 2; }
[[ ! -L "$AIES_CERT_DIR" ]] || { echo 'Refusing symlink certification directory' >&2; exit 2; }
mkdir -p -- "$AIES_CERT_DIR"
chmod 700 -- "$AIES_CERT_DIR"
DIR="$(cd -- "$AIES_CERT_DIR" && pwd -P)"
PRE="$DIR/$AIES_WORKFLOW_ID.before.json"
POST="$DIR/$AIES_WORKFLOW_ID.after.json"
[[ "$MODE" != before || ! -e "$PRE" ]] || { echo 'Baseline already exists; use new directory' >&2; exit 3; }
[[ "$MODE" != after || -f "$PRE" ]] || { echo 'Missing baseline' >&2; exit 3; }
[[ "$MODE" != after || ! -e "$POST" ]] || { echo 'After snapshot already exists' >&2; exit 3; }
TMP="$(mktemp -d)"; trap 'rm -rf -- "$TMP"' EXIT
ROOT="/api/v1/execution/workflows/$AIES_WORKFLOW_ID"
# Negative authorization: use shared curl configuration, never token argv.
# Distinct invalid token must not return successful workflow content.
SAVED_TOKEN="$AIES_WORKSPACE_CHANGES_TOKEN"
AIES_WORKSPACE_CHANGES_TOKEN='invalid-token-for-negative-test'
aies_http GET "$ROOT/acceptance" "$TMP/negative.json"
NEGATIVE_STATUS="$AIES_HTTP_STATUS"
AIES_WORKSPACE_CHANGES_TOKEN="$SAVED_TOKEN"
unset SAVED_TOKEN
[[ "$NEGATIVE_STATUS" == 401 || "$NEGATIVE_STATUS" == 403 || "$NEGATIVE_STATUS" == 404 ]] || { echo "SECURITY FAILURE: wrong token HTTP $NEGATIVE_STATUS" >&2; exit 5; }
fetch() {
  local suffix="$1" output="$2"
  aies_http GET "$ROOT/$suffix" "$output"
  [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "GET $suffix HTTP $AIES_HTTP_STATUS" >&2; exit 4; }
}
fetch acceptance "$TMP/acceptance.json"
jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId==$id and (.acceptanceStatus=="VERIFIED" or .acceptanceStatus=="INCOMPLETE") and (.tasks|type=="array") and (.tasks|length>0) and all(.tasks[]; (.taskId|type=="string") and (.status|type=="string"))' "$TMP/acceptance.json" >/dev/null || { echo 'Invalid acceptance contract' >&2; exit 4; }
fetch recovery "$TMP/recovery.json"
jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId==$id and (.tasks|type=="array")' "$TMP/recovery.json" >/dev/null || { echo 'Invalid recovery contract' >&2; exit 4; }
MATERIAL='NOT_CHECKED'
if [[ "${AIES_CERT_REQUIRE_MATERIAL:-false}" == true ]]; then
  fetch material-acceptance "$TMP/material.json"
  MATERIAL="$(jq -r '.status // "UNKNOWN"' "$TMP/material.json")"
  [[ "$MATERIAL" == MATERIAL_VERIFIED ]] || { echo "Material not verified: $MATERIAL" >&2; exit 5; }
fi
jq -n --arg id "$AIES_WORKFLOW_ID" --arg material "$MATERIAL" --slurpfile a "$TMP/acceptance.json" --slurpfile r "$TMP/recovery.json" '
  {schemaVersion:"1.0",workflowId:$id,acceptanceStatus:$a[0].acceptanceStatus,materialStatus:$material,
   tasks:([$a[0].tasks[]|{taskId,status,attempts,artifactId,artifactSha256,auditId,auditSha256,auditDecision}]|sort_by(.taskId)),
   recovery:([$r[0].tasks[]|{taskId,status,attempts,leaseExpired,action,attemptHistory:([.attemptHistory[]?|{attemptId,phase,diagnosis,modelInvocationReserved}]|sort_by(.attemptId))}]|sort_by(.taskId))}' > "$TMP/snapshot.json"
if [[ "$MODE" == before ]]; then
  # noclobber: no silent baseline replacement
  (set -C; cat "$TMP/snapshot.json" > "$PRE") || { echo 'Baseline collision' >&2; exit 3; }
  echo "Baseline captured: $PRE"
  echo 'Restart Quarkus using the SAME durable SQLite data root, then run after.'
else
  jq -S . "$PRE" > "$TMP/before.sorted"
  jq -S . "$TMP/snapshot.json" > "$TMP/after.sorted"
  cmp -s "$TMP/before.sorted" "$TMP/after.sorted" || { echo 'DRIFT: state changed; operator reconciliation required' >&2; exit 6; }
  (set -C; cat "$TMP/snapshot.json" > "$POST") || { echo 'After collision' >&2; exit 3; }
  echo "RESTART_SNAPSHOT_MATCH workflow=$AIES_WORKFLOW_ID"
  echo "baseline_sha256=$(sha256sum "$PRE" | cut -d' ' -f1)"
  echo "after_sha256=$(sha256sum "$POST" | cut -d' ' -f1)"
fi
echo "acceptance=$(jq -r '.acceptanceStatus' "$TMP/snapshot.json") material=$MATERIAL"
echo 'Scope: read-only comparison. Does not independently prove a crash, model call, or byte integrity without material check.'
