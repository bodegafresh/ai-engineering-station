#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
bash -n "$ROOT/scripts/f43g10g-negative-tests.sh" "$ROOT/scripts/f43g10g-evidence-check.sh"
AIES_WORKSPACE_CHANGES_TOKEN=G10G_SECRET bash "$ROOT/scripts/f43g10g-negative-tests.sh"
T="$(mktemp -d)"; trap 'rm -rf -- "$T"' EXIT
cat > "$T/wf-test.evidence.json" <<'JSON'
{"schemaVersion":"1.0","workflowId":"wf-test","collectedAtUtc":"2026-10-13T00:00:00Z","acceptanceStatus":"VERIFIED","materialStatus":"NOT_CHECKED","taskCount":1,"tasks":[{"taskId":"task-1"}],"recovery":[],"certification":"OBSERVATION_ONLY"}
JSON
AIES_WORKFLOW_ID=wf-test AIES_CERT_DIR="$T" bash "$ROOT/scripts/f43g10g-evidence-check.sh" > "$T/result"
grep -q OBSERVED_CANONICAL_VERIFIED "$T/result"
if AIES_WORKFLOW_ID=wf-test AIES_CERT_DIR="$T" AIES_CERT_REQUIRE_MATERIAL=true bash "$ROOT/scripts/f43g10g-evidence-check.sh" > /dev/null 2>&1; then echo 'FAIL unverified material' >&2; exit 1; fi
sed -i 's/"taskCount":1/"taskCount":2/' "$T/wf-test.evidence.json"
if AIES_WORKFLOW_ID=wf-test AIES_CERT_DIR="$T" bash "$ROOT/scripts/f43g10g-evidence-check.sh" > /dev/null 2>&1; then echo 'FAIL invalid count' >&2; exit 1; fi
echo 'PASS: evidence schema, material requirement, malformed count'
