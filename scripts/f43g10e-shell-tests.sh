#!/usr/bin/env bash
set -Eeuo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
for script in "$HERE"/scripts/*.sh; do bash -n "$script"; done
T="$(mktemp -d)"; trap 'rm -rf -- "$T"' EXIT
mkdir "$T/bin"
cat > "$T/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
: "${MOCK_ARGS:?}"
printf '%s\n' "$*" >> "$MOCK_ARGS"
output=''; url=''; config=''; prev=''
for arg in "$@"; do
  if [[ "$prev" == --output ]]; then output="$arg"; fi
  if [[ "$prev" == --config ]]; then config="$arg"; fi
  prev="$arg"
  [[ "$arg" == http://* ]] && url="$arg"
done
[[ "$config" == - ]] || exit 44
IFS= read -r header || exit 45
[[ "$header" == 'header = '* ]] || exit 46
[[ "$header" == *MOCK_SECRET_DO_NOT_LEAK* ]] || exit 47
if [[ "$url" == */planning/workflows/wf-1 ]]; then
  printf 'not JSON' > "$output"
  printf 404
elif [[ "$url" == */planning/workflows ]]; then
  printf '{"workflowId":"wf-1"}' > "$output"
  printf 201
else
  printf 'unknown' > "$output"
  printf 404
fi
MOCK
chmod +x "$T/bin/curl"
export PATH="$T/bin:$PATH" MOCK_ARGS="$T/args" AIES_WORKSPACE_CHANGES_TOKEN=MOCK_SECRET_DO_NOT_LEAK
export AIES_BASE_URL=http://127.0.0.1:8080
cat > "$T/intake.json" <<'JSON'
{"schemaVersion":"1.0","workflowId":"wf-1","objective":"Build API","domain":"software","sources":[],"requirements":[{"id":"r1"}]}
JSON
export AIES_INTAKE_FILE="$T/intake.json" AIES_MAX_POLL=1
# 404 non-JSON must not be interpreted as JSON, nor expose token in process argv.
set +e
bash "$HERE/scripts/f43g6-intake-certification.sh" create > "$T/out" 2> "$T/err"
rc=$?
set -e
[[ "$rc" != 0 ]] || { echo 'Mock should fail at planning poll' >&2; exit 1; }
[[ -f "$MOCK_ARGS" ]] || exit 1
! grep -q MOCK_SECRET_DO_NOT_LEAK "$MOCK_ARGS" || { echo 'SECRET IN ARGV' >&2; exit 1; }
! grep -q MOCK_SECRET_DO_NOT_LEAK "$T/out" "$T/err" || { echo 'SECRET IN LOG' >&2; exit 1; }
grep -q 'Workflow=wf-1' "$T/out" || exit 1
printf 'PASS: Bash syntax, non-JSON 404, no secret in curl argv or logs, bounded failure\n'
