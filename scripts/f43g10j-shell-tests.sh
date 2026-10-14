#!/usr/bin/env bash
set -Eeuo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf -- "$TMP"' EXIT
WF=wf-test
export AIES_WORKFLOW_ID="$WF" AIES_CERT_DIR="$TMP"
printf '{"workflowId":"%s","tasks":[]}' "$WF" > "$TMP/$WF.before.json"
cp "$TMP/$WF.before.json" "$TMP/$WF.after.json"
for phase in before after; do
  pid=111; ticks=100
  if [[ "$phase" == after ]]; then pid=222; ticks=200; fi
  hash="$(sha256sum "$TMP/$WF.$phase.json" | awk '{print $1}')"
  jq -n --arg wf "$WF" --arg phase "$phase" --arg hash "$hash" --argjson pid "$pid" --arg ticks "$ticks" '{schemaVersion:"1.0",certification:"PROCESS_IDENTITY_OBSERVATION",workflowId:$wf,phase:$phase,linuxPid:$pid,processStartTicks:$ticks,dataRoot:"/tmp/isolated",snapshotSha256:$hash}' > "$TMP/$WF.j10-$phase.json"
done
bash "$HERE/f43g10j-evidence-verify.sh" >/dev/null
jq '.processStartTicks="100" | .linuxPid=111' "$TMP/$WF.j10-after.json" > "$TMP/invalid.json"
cp "$TMP/invalid.json" "$TMP/$WF.j10-after.json"
if bash "$HERE/f43g10j-evidence-verify.sh" >/dev/null 2>&1; then echo 'FAIL: same PID accepted' >&2; exit 1; fi
jq '.processStartTicks="200" | .linuxPid=222' "$TMP/invalid.json" > "$TMP/$WF.j10-after.json"
printf "\n" >> "$TMP/$WF.after.json"
if bash "$HERE/f43g10j-evidence-verify.sh" >/dev/null 2>&1; then echo 'FAIL: tampered snapshot hash accepted' >&2; exit 1; fi
echo 'PASS: evidence validation, unchanged process rejection, hash tamper rejection'
