#!/usr/bin/env bash
set -Eeuo pipefail
# Read-only operator smoke. Does not claim model execution or end-to-end completion.
: "${AIES_BASE_URL:=http://127.0.0.1:8080}"
: "${AIES_WORKFLOW_ID:?Set AIES_WORKFLOW_ID to an existing workflow ID}"
: "${AIES_WORKSPACE_CHANGES_TOKEN:?Set AIES_WORKSPACE_CHANGES_TOKEN (32+ characters)}"
[[ ${#AIES_WORKSPACE_CHANGES_TOKEN} -ge 32 ]] || { echo 'ERROR: short operator token' >&2; exit 2; }
[[ "$AIES_WORKFLOW_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || { echo 'ERROR: invalid workflow ID' >&2; exit 2; }
for bin in curl java; do command -v "$bin" >/dev/null || { echo "ERROR: missing $bin" >&2; exit 2; }; done
[[ "$AIES_BASE_URL" == http://127.0.0.1:* || "$AIES_BASE_URL" == http://localhost:* ]] || { echo 'ERROR: only loopback HTTP is allowed' >&2; exit 2; }
endpoint="$AIES_BASE_URL/api/v1/execution/workflows/$AIES_WORKFLOW_ID/acceptance"
body="$(mktemp)"; trap 'rm -f "$body"' EXIT
status="$(curl --silent --show-error --max-time 15 --output "$body" --write-out '%{http_code}' \
  --header "X-AIES-Workspace-Token: $AIES_WORKSPACE_CHANGES_TOKEN" "$endpoint")"
[[ "$status" == 200 ]] || { echo "ERROR: acceptance endpoint returned HTTP $status" >&2; exit 1; }
if command -v jq >/dev/null; then
  jq -e --arg wf "$AIES_WORKFLOW_ID" '.workflowId == $wf and (.acceptanceStatus == "VERIFIED" or .acceptanceStatus == "INCOMPLETE") and (.tasks | type == "array")' "$body" >/dev/null || { echo 'ERROR: invalid acceptance response' >&2; exit 1; }
  jq '{workflowId,acceptanceStatus,taskCount:(.tasks|length),limitations}' "$body"
else
  echo 'HTTP 200; install jq to validate the manifest schema and view redacted summary.'
fi
echo 'PASS: read-only operator acceptance endpoint; NOT proof of real-model E2E.'
