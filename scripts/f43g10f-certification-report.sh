#!/usr/bin/env bash
# G10-F: read-only evidence bundle from production operator endpoints.
set -Eeuo pipefail
umask 077
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/f43g10e-http.sh"
: "${AIES_WORKFLOW_ID:?Existing imported workflow ID required}"
: "${AIES_CERT_DIR:?Private evidence directory required}"
aies_id "$AIES_WORKFLOW_ID" || exit 2
[[ ! -L "$AIES_CERT_DIR" ]] || { echo 'Refusing symlink evidence directory' >&2; exit 2; }
mkdir -p -- "$AIES_CERT_DIR"; chmod 700 -- "$AIES_CERT_DIR"
DIR="$(cd -- "$AIES_CERT_DIR" && pwd -P)"
[[ ! -e "$DIR/$AIES_WORKFLOW_ID.evidence.json" ]] || { echo 'Evidence already exists; use a new directory' >&2; exit 3; }
TMP="$(mktemp -d)"; trap 'rm -rf -- "$TMP"' EXIT
ROOT="/api/v1/execution/workflows/$AIES_WORKFLOW_ID"
fetch() {
  local endpoint="$1"
  aies_http GET "$ROOT/$endpoint" "$TMP/$endpoint.json"
  [[ "$AIES_HTTP_STATUS" == 200 ]] || { echo "GET $endpoint HTTP $AIES_HTTP_STATUS" >&2; exit 4; }
}
fetch acceptance
fetch recovery
jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId==$id and (.tasks|type=="array")' "$TMP/acceptance.json" >/dev/null || exit 4
jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId==$id and (.tasks|type=="array")' "$TMP/recovery.json" >/dev/null || exit 4
MATERIAL='NOT_CHECKED'
if [[ "${AIES_CERT_REQUIRE_MATERIAL:-false}" == true ]]; then
  fetch material-acceptance
  MATERIAL="$(jq -r '.status // "UNKNOWN"' "$TMP/material-acceptance.json")"
fi
jq -n --arg id "$AIES_WORKFLOW_ID" --arg material "$MATERIAL" \
  --arg collected "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --slurpfile a "$TMP/acceptance.json" --slurpfile r "$TMP/recovery.json" '
  {schemaVersion:"1.0",workflowId:$id,collectedAtUtc:$collected,
   acceptanceStatus:$a[0].acceptanceStatus,materialStatus:$material,
   taskCount:($a[0].tasks|length),
   tasks:([$a[0].tasks[]|{taskId,status,attempts,artifactId,artifactSha256,auditId,auditSha256,auditDecision}]|sort_by(.taskId)),
   recovery:([$r[0].tasks[]|{taskId,status,leaseExpired,action,attemptHistory:([.attemptHistory[]?|{attemptId,phase,diagnosis,modelInvocationReserved}]|sort_by(.attemptId))}]|sort_by(.taskId)),
   certification:"OBSERVATION_ONLY",limits:["Not proof of model identity or timing","Not proof of restart unless separate before/after performed","Not proof of material bytes unless MATERIAL_VERIFIED","Not proof of saturation or late-reply fencing"]}' > "$TMP/evidence.json"
(set -C; cat "$TMP/evidence.json" > "$DIR/$AIES_WORKFLOW_ID.evidence.json") || exit 3
sha256sum "$DIR/$AIES_WORKFLOW_ID.evidence.json"
[[ "$(jq -r '.acceptanceStatus' "$TMP/evidence.json")" == VERIFIED && "$MATERIAL" != INCOMPLETE ]] || { echo 'INCOMPLETE: observation recorded, no certification claim' >&2; exit 3; }
echo 'Canonical acceptance observed. Full F4 certification still requires runtime negative evidence and sign-off.'
