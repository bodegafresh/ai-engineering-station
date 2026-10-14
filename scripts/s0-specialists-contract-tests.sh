#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
command -v jq >/dev/null || { echo 'FAIL: jq required' >&2; exit 2; }
for file in "$ROOT"/docs/contracts/*.json "$ROOT"/docs/fixtures/specialists/*.json; do jq -e . "$file" >/dev/null; done
fixture="$ROOT/docs/fixtures/specialists/legacy-catalog.json"
jq -e '
  .schemaVersion == "1.0" and (.specialists | length == 4) and
  (.specialists | map(.id) | sort == ["experiment-analyst","requirements-auditor","researcher","software-engineer"]) and
  (.specialists | map(select(.id=="researcher" and .maxConcurrent==2 and .costUnits==1 and .capabilities==["research"])) | length==1) and
  (.specialists | map(select(.id=="requirements-auditor" and .maxConcurrent==1 and .costUnits==2 and (.capabilities|sort)==["audit","requirements"])) | length==1) and
  (.specialists | map(select(.id=="software-engineer" and .maxConcurrent==1 and .costUnits==3 and (.capabilities|sort)==["implementation","software"])) | length==1) and
  (.specialists | map(select(.id=="experiment-analyst" and .maxConcurrent==1 and .costUnits==2 and (.capabilities|sort)==["experiments","statistics"])) | length==1)
' "$fixture" >/dev/null
valid="$ROOT/docs/fixtures/specialists/valid-software-profile.json"
invalid="$ROOT/docs/fixtures/specialists/invalid-implicit-permission.json"
jq -e '.schemaVersion=="2.0" and .kind=="SpecialistProfile" and (.competencies|length>0) and (has("grantAllTools")|not)' "$valid" >/dev/null
if jq -e 'has("grantAllTools")|not' "$invalid" >/dev/null; then echo 'FAIL: invalid permission fixture accepted' >&2; exit 1; fi
jq -e '(.properties|has("grantAllTools")|not) and .additionalProperties==false' "$ROOT/docs/contracts/specialist-profile-v2.schema.json" >/dev/null
printf '%s\n' 'PASS: S0 JSON parse, legacy seed contract, valid/invalid fixture checks (not full JSON Schema validation)'
