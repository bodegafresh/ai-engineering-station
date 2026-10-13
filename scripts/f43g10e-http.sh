#!/usr/bin/env bash
# Shared operator HTTP transport. Source from other scripts; never execute standalone.
[[ ${BASH_SOURCE[0]} != "$0" ]] || { echo 'Source this helper' >&2; exit 2; }
: "${AIES_WORKSPACE_CHANGES_TOKEN:?Centralized operator token required}"
AIES_BASE_URL="${AIES_BASE_URL:-http://127.0.0.1:8080}"
[[ "$AIES_BASE_URL" =~ ^http://(127\.0\.0\.1|localhost):[0-9]{1,5}$ ]] || { echo 'Only explicit loopback host:port allowed' >&2; exit 2; }
[[ "$AIES_WORKSPACE_CHANGES_TOKEN" != *$'\n'* && "$AIES_WORKSPACE_CHANGES_TOKEN" != *$'\r'* ]] || { echo 'Invalid token' >&2; exit 2; }
command -v curl >/dev/null && command -v jq >/dev/null || { echo 'curl and jq required' >&2; exit 2; }
# Curl reads the secret from stdin configuration; it never appears in curl argv.
# Callers must check AIES_HTTP_STATUS before parsing body; 404 may be non-JSON.
aies_http() {
  local method="$1" path="$2" output="$3" body="${4:-}" cfg status
  [[ "$method" == GET || "$method" == POST ]] || return 2
  [[ "$path" == /* && "$path" != *$'\n'* && "$path" != *$'\r'* ]] || return 2
  [[ "$output" == /* ]] || { echo 'Response output must be absolute path' >&2; return 2; }
  local -a args=(--silent --show-error --noproxy '*' --connect-timeout 5 --max-time 210 --request "$method" --output "$output" --write-out '%{http_code}' --config -)
  if [[ -n "$body" ]]; then
    [[ -f "$body" && ! -L "$body" ]] || return 2
    args+=(--header 'Content-Type: application/json' --data-binary "@$body")
  fi
  # JSON-escape header value using jq to avoid curl config injection.
  cfg="$(jq -n --arg h "X-AIES-Workspace-Token: $AIES_WORKSPACE_CHANGES_TOKEN" '$h')" || return 2
  status="$(printf 'header = %s\n' "$cfg" | curl "${args[@]}" "$AIES_BASE_URL$path")" || {
    echo 'Transport ambiguity: inspect durable state before retrying any mutation' >&2; return 4;
  }
  [[ "$status" =~ ^[0-9]{3}$ ]] || { echo 'Malformed HTTP status' >&2; return 4; }
  AIES_HTTP_STATUS="$status"
  if [[ "$status" == 200 || "$status" == 201 ]]; then
    jq -e 'type=="object"' "$output" >/dev/null || { echo "Invalid JSON object for HTTP $status" >&2; return 4; }
  fi
}
aies_id() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$ ]]; }
