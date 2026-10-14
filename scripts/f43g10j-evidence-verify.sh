#!/usr/bin/env bash
# Offline read-only verification of G10-J evidence and G9 snapshots.
set -Eeuo pipefail
umask 077
: "${AIES_WORKFLOW_ID:?}"
: "${AIES_CERT_DIR:?}"
[[ "$AIES_WORKFLOW_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$ ]] || exit 2
[[ -d "$AIES_CERT_DIR" && ! -L "$AIES_CERT_DIR" ]] || exit 2
DIR="$(cd -- "$AIES_CERT_DIR" && pwd -P)"
BASE="$DIR/$AIES_WORKFLOW_ID"
for file in "$BASE.j10-before.json" "$BASE.j10-after.json" "$BASE.before.json" "$BASE.after.json"; do
  [[ -f "$file" && ! -L "$file" ]] || { echo 'Missing or symlink evidence' >&2; exit 3; }
done
jq -e --slurpfile b "$BASE.j10-before.json" --slurpfile a "$BASE.j10-after.json" --arg id "$AIES_WORKFLOW_ID" '
  ($b|length)==1 and ($a|length)==1 and
  $b[0].schemaVersion=="1.0" and $a[0].schemaVersion=="1.0" and
  $b[0].workflowId==$id and $a[0].workflowId==$id and
  $b[0].phase=="before" and $a[0].phase=="after" and
  $b[0].certification=="PROCESS_IDENTITY_OBSERVATION" and
  $a[0].certification=="PROCESS_IDENTITY_OBSERVATION" and
  $b[0].dataRoot==$a[0].dataRoot and
  ($b[0].linuxPid!=$a[0].linuxPid or $b[0].processStartTicks!=$a[0].processStartTicks) and
  ($b[0].snapshotSha256|test("^[0-9a-f]{64}$")) and ($a[0].snapshotSha256|test("^[0-9a-f]{64}$"))
' -n >/dev/null || { echo 'Invalid process evidence' >&2; exit 4; }
for mode in before after; do
  observed="$(jq -r .snapshotSha256 "$BASE.j10-$mode.json")"
  actual="$(sha256sum "$BASE.$mode.json" | awk '{print $1}')"
  [[ "$observed" == "$actual" ]] || { echo "Snapshot hash mismatch: $mode" >&2; exit 5; }
done
cmp -s <(jq -S . "$BASE.before.json") <(jq -S . "$BASE.after.json") || { echo 'Snapshot drift' >&2; exit 5; }
sha256sum "$BASE.j10-before.json" "$BASE.j10-after.json"
echo 'PROCESS_RESTART_EVIDENCE_VALID (operator attestation and full F4 certification remain pending)'
