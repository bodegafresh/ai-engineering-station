#!/usr/bin/env bash
# Deterministic transport contract tests: no running Quarkus or Ollama required.
set -Eeuo pipefail
umask 077
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
source "$ROOT/scripts/f43g10e-http.sh"
T="$(mktemp -d)"; trap 'rm -rf -- "$T"' EXIT
mkdir "$T/bin"
cat > "$T/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
: "${AIES_G10G_CALLS:?}"
printf '%s\n' "$*" >> "$AIES_G10G_CALLS"
output='' method='' config='' prev=''
for a in "$@"; do
 case "$prev" in --output) output="$a";; --request) method="$a";; --config) config="$a";; esac
 prev="$a"
done
[[ "$config" == - && -n "$output" ]] || exit 88
IFS= read -r cfg || exit 89
[[ "$cfg" == 'header = '* && "$cfg" == *'G10G_SECRET'* ]] || exit 90
case "${AIES_G10G_CASE:?}" in
 unauthorized) printf '<html>not found</html>' > "$output"; printf 404;;
 approved) printf '{"workflowId":"wf-test","status":"READY_FOR_ASSIGNMENT"}' > "$output"; printf 200;;
 malformed) printf 'not-json' > "$output"; printf 200;;
 timeout) printf 'ambiguous' > "$output"; exit 28;;
 conflict) printf '{"error":"conflict"}' > "$output"; printf 409;;
 rate_limit) printf '{"error":"rate limit"}' > "$output"; printf 429;;
 *) exit 91;;
esac
MOCK
chmod 700 "$T/bin/curl"
export PATH="$T/bin:$PATH" AIES_G10G_CALLS="$T/calls" AIES_WORKSPACE_CHANGES_TOKEN=G10G_SECRET
export AIES_BASE_URL=http://127.0.0.1:8080
# source helper again after setting mock configuration
source "$ROOT/scripts/f43g10e-http.sh"
assert_status() {
 local scenario="$1" expected="$2" rc=0
 export AIES_G10G_CASE="$scenario"
 aies_http GET '/api/v1/planning/workflows/wf-test' "$T/$scenario.json" || rc=$?
 [[ "$rc" == 0 && "$AIES_HTTP_STATUS" == "$expected" ]] || { echo "FAIL $scenario rc=$rc status=${AIES_HTTP_STATUS:-none}" >&2; exit 1; }
}
assert_status unauthorized 404
assert_status approved 200
assert_status conflict 409
assert_status rate_limit 429
export AIES_G10G_CASE=malformed
if (aies_http GET '/api/v1/planning/workflows/wf-test' "$T/malformed.json") >"$T/out" 2>"$T/err"; then echo 'FAIL malformed success' >&2; exit 1; fi
export AIES_G10G_CASE=timeout
if (aies_http POST '/api/v1/planning/workflows' "$T/timeout.json") >"$T/out" 2>"$T/err"; then echo 'FAIL timeout success' >&2; exit 1; fi
! grep -q G10G_SECRET "$T/calls" "$T/out" "$T/err" || { echo 'FAIL secret disclosure' >&2; exit 1; }
if aies_id '../traversal'; then echo 'FAIL ID traversal' >&2; exit 1; fi
if aies_id 'wf/test'; then echo 'FAIL ID slash' >&2; exit 1; fi
aies_id 'wf-valid_01' || exit 1
printf 'PASS: 404 HTML, 200 JSON, 409 conflict, 429 rate-limit, malformed 200, ambiguous POST, token redaction, identifier validation\n'
printf 'LIMIT: mocked HTTP; does not prove production recovery, lease fencing, model identity, or rate-limit behavior\n'
