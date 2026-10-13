#!/usr/bin/env bash
# F4.3-G9: read-only restart certification. No workflow mutation or automatic retry.
set -Eeuo pipefail
umask 077
for command_name in curl jq sha256sum mktemp; do
  command -v "$command_name" >/dev/null || { echo "Missing $command_name" >&2; exit 2; }
done
: "${AIES_WORKSPACE_CHANGES_TOKEN:?Centralized operator token required}"
: "${AIES_WORKFLOW_ID:?Existing imported workflow ID required}"
: "${AIES_CERT_DIR:?Persistent certification directory required}"
[[ "$AIES_WORKFLOW_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$ ]] || { echo 'Invalid workflow ID' >&2; exit 2; }
BASE="${AIES_BASE_URL:-http://127.0.0.1:8080}"
[[ "$BASE" =~ ^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]] || { echo 'Only explicit loopback HTTP port allowed' >&2; exit 2; }
MODE="${1:-}"
[[ "$MODE" == before || "$MODE" == after ]] || { echo 'Usage: script before|after' >&2; exit 2; }
[[ ! -L "$AIES_CERT_DIR" ]] || { echo 'Refusing symlink certification directory' >&2; exit 2; }
mkdir -p -- "$AIES_CERT_DIR"; chmod 700 -- "$AIES_CERT_DIR"
DIR="$(cd -- "$AIES_CERT_DIR" && pwd -P)"
[[ -d "$DIR" && ! -L "$DIR" ]] || exit 2
PRE="$DIR/$AIES_WORKFLOW_ID.before.json"
POST="$DIR/$AIES_WORKFLOW_ID.after.json"
TEMP="$(mktemp -d)"; trap 'rm -rf -- "$TEMP"' EXIT
api() {
  local path="$1" file="$2" token="$3" code
  code="$(curl --silent --show-error --noproxy '*' --connect-timeout 5 --max-time 45 \
    --request GET --header "X-AIES-Workspace-Token: $token" \
    --output "$file" --write-out '%{http_code}' "$BASE$path")" || {
      echo 'Transport failure; no state was changed' >&2; exit 4;
    }
  printf '%s' "$code"
}
ROOT="/api/v1/execution/workflows/$AIES_WORKFLOW_ID"
if [[ "$MODE" == after && ! -f "$PRE" ]]; then
  echo 'Missing before snapshot; no restart comparison possible' >&2; exit 3
fi
if [[ "$MODE" == before && -e "$PRE" ]]; then
  echo 'Refusing to overwrite baseline; use a new certification directory' >&2; exit 3
fi
# Negative access check: a wrong token must not disclose workflow state.
BAD_TOKEN="invalid-token-for-negative-test"
status="$(api "$ROOT/acceptance" "$TEMP/negative.json" "$BAD_TOKEN")"
[[ "$status" == 404 || "$status" == 401 || "$status" == 403 ]] || {
  echo "SECURITY FAILURE: wrong token returned HTTP $status" >&2; exit 5;
}
# Capture only bounded, sanitized state; do not persist tokens or full model output.
status="$(api "$ROOT/acceptance" "$TEMP/acceptance.json" "$AIES_WORKSPACE_CHANGES_TOKEN")"
[[ "$status" == 200 ]] || { echo "Acceptance HTTP $status" >&2; exit 4; }
jq -e --arg id "$AIES_WORKFLOW_ID" '
  .workflowId==$id and
  (.acceptanceStatus=="VERIFIED" or .acceptanceStatus=="INCOMPLETE") and
  (.tasks|type=="array") and
  all(.tasks[]; (.taskId|type=="string") and (.status|type=="string"))
' "$TEMP/acceptance.json" >/dev/null || { echo 'Invalid acceptance contract' >&2; exit 4; }
status="$(api "$ROOT/recovery" "$TEMP/recovery.json" "$AIES_WORKSPACE_CHANGES_TOKEN")"
[[ "$status" == 200 ]] || { echo "Recovery HTTP $status; enable recovery endpoint" >&2; exit 4; }
jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId==$id and (.tasks|type=="array")' "$TEMP/recovery.json" >/dev/null || exit 4
material="NOT_CHECKED"
if [[ "${AIES_CERT_REQUIRE_MATERIAL:-false}" == true ]]; then
  status="$(api "$ROOT/material-acceptance" "$TEMP/material.json" "$AIES_WORKSPACE_CHANGES_TOKEN")"
  [[ "$status" == 200 ]] || { echo "Material HTTP $status (possible tamper or feature disabled)" >&2; exit 5; }
  material="$(jq -r '.status' "$TEMP/material.json")"
  [[ "$material" == MATERIAL_VERIFIED ]] || { echo "Material not verified: $material" >&2; exit 5; }
fi
jq -n --arg id "$AIES_WORKFLOW_ID" --arg material "$material" \
  --slurpfile a "$TEMP/acceptance.json" --slurpfile r "$TEMP/recovery.json" '
  {schemaVersion:"1.0", workflowId:$id, acceptanceStatus:$a[0].acceptanceStatus,
   materialStatus:$material,
   tasks:([$a[0].tasks[]|{taskId,status,attempts,artifactId,artifactSha256,auditId,auditSha256,auditDecision}]|sort_by(.taskId)),
   recovery:([$r[0].tasks[]|{taskId,status,attempts,leaseExpired,action,
     attemptHistory:([.attemptHistory[]?|{attemptId,phase,diagnosis,modelInvocationReserved}]|sort_by(.attemptId))}]|sort_by(.taskId))}' > "$TEMP/snapshot.json"
[[ "$(jq -r '.tasks|length' "$TEMP/snapshot.json")" -gt 0 ]] || { echo 'No tasks' >&2; exit 4; }
if [[ "$MODE" == before ]]; then
  mv -n -- "$TEMP/snapshot.json" "$PRE"
  [[ -f "$PRE" ]] || exit 4
  echo "Baseline captured: $PRE"
  echo 'Restart Quarkus with the SAME persistent SQLite data root, then run after.'
else
  # Compare all persisted IDs, hashes and recovery phases; differences require operator inspection.
  jq -S . "$PRE" > "$TEMP/pre.normalized"
  jq -S . "$TEMP/snapshot.json" > "$TEMP/post.normalized"
  if ! cmp -s "$TEMP/pre.normalized" "$TEMP/post.normalized"; then
    echo 'DRIFT: persistent state changed across restart; refusing certification' >&2
    exit 6
  fi
  [[ ! -e "$POST" ]] || { echo 'Refusing overwrite of after snapshot' >&2; exit 3; }
  mv -n -- "$TEMP/snapshot.json" "$POST"
  echo "RESTART_SNAPSHOT_MATCH workflow=$AIES_WORKFLOW_ID"
  echo "baseline_sha256=$(sha256sum "$PRE" | cut -d' ' -f1)"
  echo "after_sha256=$(sha256sum "$POST" | cut -d' ' -f1)"
fi
echo "acceptance=$(jq -r '.acceptanceStatus' "$([[ "$MODE" == before ]] && printf %s "$PRE" || printf %s "$POST")") material=$material"
echo 'Scope: read-only state comparison; does not prove a crash occurred, model timing, or 429 behavior.'
