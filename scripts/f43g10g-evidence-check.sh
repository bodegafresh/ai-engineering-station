#!/usr/bin/env bash
# Verify a G10-F observation and optional restart snapshots without modifying them.
set -Eeuo pipefail
umask 077
for bin in jq sha256sum cmp; do command -v "$bin" >/dev/null || { echo "Missing $bin" >&2; exit 2; }; done
: "${AIES_WORKFLOW_ID:?Existing workflow ID required}"
: "${AIES_CERT_DIR:?Evidence directory required}"
[[ "$AIES_WORKFLOW_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$ ]] || exit 2
[[ -d "$AIES_CERT_DIR" && ! -L "$AIES_CERT_DIR" ]] || exit 2
DIR="$(cd -- "$AIES_CERT_DIR" && pwd -P)"
FILE="$DIR/$AIES_WORKFLOW_ID.evidence.json"
[[ -f "$FILE" && ! -L "$FILE" ]] || { echo 'Missing G10-F evidence; collect actual observation first' >&2; exit 3; }
jq -e --arg id "$AIES_WORKFLOW_ID" '
 .schemaVersion=="1.0" and .workflowId==$id and .certification=="OBSERVATION_ONLY"
 and (.collectedAtUtc|type=="string")
 and (.tasks|type=="array") and (.recovery|type=="array")
 and .taskCount==(.tasks|length)
 and (.acceptanceStatus=="VERIFIED" or .acceptanceStatus=="INCOMPLETE")
 and (.materialStatus|type=="string")
' "$FILE" >/dev/null || { echo 'Invalid G10-F observation schema' >&2; exit 4; }
sha256sum "$FILE"
status="$(jq -r '.acceptanceStatus' "$FILE")"
material="$(jq -r '.materialStatus' "$FILE")"
[[ "$status" == VERIFIED ]] || { echo "INCOMPLETE: acceptance=$status" >&2; exit 3; }
if [[ "${AIES_CERT_REQUIRE_MATERIAL:-false}" == true ]]; then
 [[ "$material" == MATERIAL_VERIFIED ]] || { echo "INCOMPLETE: material=$material" >&2; exit 3; }
fi
PRE="$DIR/$AIES_WORKFLOW_ID.before.json"; POST="$DIR/$AIES_WORKFLOW_ID.after.json"
if [[ -f "$PRE" || -f "$POST" ]]; then
 [[ -f "$PRE" && -f "$POST" && ! -L "$PRE" && ! -L "$POST" ]] || { echo 'Incomplete restart pair' >&2; exit 3; }
 jq -S . "$PRE" > /dev/null && jq -S . "$POST" > /dev/null || exit 4
 cmp -s <(jq -S . "$PRE") <(jq -S . "$POST") || { echo 'Restart snapshot drift' >&2; exit 5; }
 echo 'RESTART_SNAPSHOTS_MATCH (operator must independently confirm actual process restart)'
fi
echo 'OBSERVED_CANONICAL_VERIFIED; F4 remains OPEN until real negative runtime evidence and operator sign-off.'
