#!/usr/bin/env bash
# G10-J: read-only process identity proof paired with G9 snapshots.
set -Eeuo pipefail
umask 077
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/f43g10e-http.sh"
: "${AIES_WORKFLOW_ID:?Workflow ID required}"
: "${AIES_CERT_DIR:?Private certification directory required}"
: "${AIES_QUARKUS_PID:?Explicit Quarkus JVM PID required}"
: "${AIES_DATA_ROOT:?Explicit durable SQLite root required}"
aies_id "$AIES_WORKFLOW_ID" || { echo 'Invalid workflow ID' >&2; exit 2; }
[[ "$AIES_QUARKUS_PID" =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid PID' >&2; exit 2; }
[[ "$AIES_CERT_DIR" != / && ! -L "$AIES_CERT_DIR" ]] || exit 2
[[ -d "$AIES_DATA_ROOT" && ! -L "$AIES_DATA_ROOT" ]] || { echo 'Data root must exist and not be a symlink' >&2; exit 2; }
DATA_ROOT="$(cd -- "$AIES_DATA_ROOT" && pwd -P)"
[[ "$DATA_ROOT" != / ]] || exit 2
[[ -r "/proc/$AIES_QUARKUS_PID/stat" && -r "/proc/$AIES_QUARKUS_PID/cmdline" ]] || { echo 'PID not accessible via Linux /proc' >&2; exit 2; }
command -v sha256sum >/dev/null || exit 2
MODE="${1:-}"
[[ "$MODE" == before || "$MODE" == after ]] || { echo 'Usage: runtime-restart.sh before|after' >&2; exit 2; }
# Linux /proc starttime field 22, after stripping comm enclosed in parentheses.
START="$(sed -E 's/^.*\) //' "/proc/$AIES_QUARKUS_PID/stat" | awk '{print $20}')"
[[ "$START" =~ ^[0-9]+$ ]] || { echo 'Cannot identify process start ticks' >&2; exit 2; }
CMDLINE="$(tr '\0' ' ' < "/proc/$AIES_QUARKUS_PID/cmdline")"
[[ "$CMDLINE" == *java* && ( "$CMDLINE" == *quarkus* || "$CMDLINE" == *Quarkus* || "$CMDLINE" == *control-plane* ) ]] || { echo 'PID does not look like Quarkus JVM' >&2; exit 2; }
# Hash cmdline, never persist arguments: they could include secrets.
CMDHASH="$(sha256sum "/proc/$AIES_QUARKUS_PID/cmdline" | awk '{print $1}')"
[[ ! -L "$AIES_CERT_DIR" ]] || exit 2
mkdir -p -- "$AIES_CERT_DIR"
chmod 700 -- "$AIES_CERT_DIR"
DIR="$(cd -- "$AIES_CERT_DIR" && pwd -P)"
BASE="$DIR/$AIES_WORKFLOW_ID"
TARGET="$BASE.j10-$MODE.json"
[[ ! -e "$TARGET" && ! -L "$TARGET" ]] || { echo 'Evidence collision' >&2; exit 3; }
if [[ "$MODE" == after ]]; then
  [[ -f "$BASE.j10-before.json" && -f "$BASE.before.json" ]] || { echo 'Missing before and G9 snapshot pair' >&2; exit 3; }
  [[ ! -L "$BASE.j10-before.json" && ! -L "$BASE.before.json" && ! -L "$BASE.after.json" ]] || exit 3
fi
TMP="$(mktemp -d)"; trap 'rm -rf -- "$TMP"' EXIT
# Invoke existing G9 read-only endpoint snapshot. Never restart/kill a process automatically.
if [[ "$MODE" == before ]]; then
  bash "$HERE/f43g9-restart-certify.sh" before
else
  bash "$HERE/f43g9-restart-certify.sh" after
fi
jq -n --arg workflow "$AIES_WORKFLOW_ID" --arg mode "$MODE" --arg root "$DATA_ROOT" \
  --arg pid "$AIES_QUARKUS_PID" --arg ticks "$START" --arg cmdhash "$CMDHASH" \
  --arg captured "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg snapshotHash "$(sha256sum "$BASE.$MODE.json" | awk '{print $1}')" \
  '{schemaVersion:"1.0",certification:"PROCESS_IDENTITY_OBSERVATION",workflowId:$workflow,phase:$mode,capturedAtUtc:$captured,linuxPid:($pid|tonumber),processStartTicks:$ticks,commandLineSha256:$cmdhash,dataRoot:$root,snapshotSha256:$snapshotHash,limitations:["Operator supplied PID and durable root; application configuration is not independently attested","No process termination or mutation performed","PID metadata and HTTP snapshot are not atomic","No Ollama identity or timing claim"]}' > "$TMP/evidence.json"
if [[ "$MODE" == after ]]; then
  jq -e --arg workflow "$AIES_WORKFLOW_ID" --arg root "$DATA_ROOT" --arg pid "$AIES_QUARKUS_PID" --arg ticks "$START" '
    .workflowId==$workflow and .dataRoot==$root and
    ((.linuxPid|tostring)!=$pid or .processStartTicks!=$ticks)' "$BASE.j10-before.json" >/dev/null || { echo 'PROCESS RESTART NOT PROVEN: same identity or changed root' >&2; exit 5; }
  cmp -s <(jq -S . "$BASE.before.json") <(jq -S . "$BASE.after.json") || { echo 'Snapshot drift' >&2; exit 5; }
fi
(set -C; cat "$TMP/evidence.json" > "$TARGET") || exit 3
sha256sum "$TARGET"
if [[ "$MODE" == before ]]; then
  echo 'Baseline captured. Operator must stop and restart Quarkus with same durable root, then supply NEW PID.'
else
  echo 'PROCESS_IDENTITY_CHANGED_AND_SNAPSHOTS_MATCH (operator must attest root is actually configured in Quarkus)'
fi
