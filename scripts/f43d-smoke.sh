#!/usr/bin/env bash
set -euo pipefail
# Read-only smoke. Never print token or response bodies containing project data.
: "${AIES_SMOKE_WORKFLOW_ID:?Set AIES_SMOKE_WORKFLOW_ID}"
: "${AIES_WORKSPACE_CHANGES_TOKEN:?Set AIES_WORKSPACE_CHANGES_TOKEN}"
BASE="${AIES_SMOKE_BASE_URL:-http://127.0.0.1:8080}"
case "$BASE" in http://127.0.0.1:*|http://localhost:*) ;; *) echo 'ERROR: local loopback URL required' >&2; exit 2;; esac
if ! [[ "$AIES_SMOKE_WORKFLOW_ID" =~ ^[A-Za-z0-9_-]{1,128}$ ]]; then echo 'ERROR: invalid workflow ID' >&2; exit 2; fi
command -v curl >/dev/null || { echo 'ERROR: curl required' >&2; exit 2; }
command -v jq >/dev/null || { echo 'ERROR: jq required' >&2; exit 2; }
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
CODE="$(curl --silent --show-error --max-time 20 --output "$TMP" --write-out '%{http_code}' \
  -H "X-AIES-Workspace-Token: ${AIES_WORKSPACE_CHANGES_TOKEN}" \
  "$BASE/api/v1/execution/workflows/$AIES_SMOKE_WORKFLOW_ID/material-acceptance")"
if [[ "$CODE" != 200 ]]; then echo "ERROR: HTTP $CODE (check flags, token and workflow)" >&2; exit 1; fi
jq -e --arg wf "$AIES_SMOKE_WORKFLOW_ID" \
  '.workflowId == $wf and (.status == "MATERIAL_VERIFIED" or .status == "INCOMPLETE") and (.checks | type == "array") and (.limitations | type == "array")' \
  "$TMP" >/dev/null || { echo 'ERROR: invalid response schema' >&2; exit 1; }
echo 'F4.3-D material acceptance endpoint: schema OK (read-only).'
jq -r '"status=" + .status + " checks=" + (.checks | length | tostring)' "$TMP"
