#!/usr/bin/env bash
# Read-only, authenticated operational acceptance check. Does not create approvals.
set -Eeuo pipefail
for command in curl jq mktemp; do command -v "$command" >/dev/null || { echo "Missing $command" >&2; exit 2; }; done
: "${AIES_BASE_URL:=http://127.0.0.1:8080}"
: "${AIES_WORKFLOW_ID:?Set AIES_WORKFLOW_ID to an existing operator-approved workflow}"
: "${AIES_WORKSPACE_TOKEN:?Set AIES_WORKSPACE_TOKEN without putting it on the command line}"
[[ "$AIES_BASE_URL" =~ ^http://(127\.0\.0\.1|localhost)(:[0-9]+)?$ ]] || { echo 'Only loopback HTTP is supported' >&2; exit 2; }
[[ "$AIES_WORKFLOW_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$ ]] || { echo 'Invalid workflow ID' >&2; exit 2; }
[[ -n "$AIES_WORKSPACE_TOKEN" ]] || exit 2
umask 077
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
endpoint="$AIES_BASE_URL/api/v1/execution/workflows/$AIES_WORKFLOW_ID"
# Invalid-token access must be rejected. No bearer value is logged.
invalid_status=$(curl --silent --show-error --max-time 10 --output "$TMP/invalid.json" --write-out '%{http_code}' --header 'X-AIES-Workspace-Token: invalid-f43g4-token' "$endpoint/acceptance")
[[ "$invalid_status" == 401 || "$invalid_status" == 403 ]] || { echo "FAIL: invalid-token access returned HTTP $invalid_status" >&2; exit 1; }
for resource in tasks acceptance; do
  status=$(curl --silent --show-error --max-time 30 --output "$TMP/$resource.json" --write-out '%{http_code}' --header "X-AIES-Workspace-Token: $AIES_WORKSPACE_TOKEN" "$endpoint/$resource")
  [[ "$status" == 200 ]] || { echo "FAIL: $resource HTTP $status" >&2; exit 1; }
done
jq -e --arg id "$AIES_WORKFLOW_ID" '.workflowId == $id and (.tasks | type == "array") and (.acceptanceStatus == "VERIFIED" or .acceptanceStatus == "INCOMPLETE")' "$TMP/acceptance.json" >/dev/null
jq -e 'type == "array"' "$TMP/tasks.json" >/dev/null
jq -e --slurpfile state "$TMP/tasks.json" '(.tasks|length) == ($state[0]|length) and ([.tasks[].taskId]|sort) == ([$state[0][].taskId]|sort)' "$TMP/acceptance.json" >/dev/null
jq -e 'if .acceptanceStatus == "VERIFIED" then (.tasks|length)>0 and all(.tasks[]; .status == "COMPLETED" and .auditDecision == "APPROVED" and (.artifactSha256|type)=="string" and (.auditSha256|type)=="string") else true end' "$TMP/acceptance.json" >/dev/null
jq '{workflowId,acceptanceStatus,taskCount:(.tasks|length),statuses:[.tasks[].status],limitations}' "$TMP/acceptance.json"
echo "PASS: authenticated, read-only acceptance snapshot and invalid-token rejection"
echo 'NOTE: This does not prove full intake/plan/worker/Ollama execution or atomic cross-DB snapshot.'
