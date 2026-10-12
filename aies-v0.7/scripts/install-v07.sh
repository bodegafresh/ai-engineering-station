#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-.}"
cd "$ROOT"
test -f settings.gradle.kts || { echo 'Run against ai-engineering-station root' >&2; exit 2; }
SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET=apps/control-plane/src/main/java/dev/aies/control/LocalModelClient.java
mkdir -p "$(dirname "$TARGET")"
if [ -f "$TARGET" ]; then cp "$TARGET" "$TARGET.v06.bak"; fi
cp "$SOURCE/$TARGET" "$TARGET"
PROPS=apps/control-plane/src/main/resources/application.properties
if ! grep -q '^aies.ai.default-provider=' "$PROPS"; then
cat >> "$PROPS" <<'PROPERTIES'

# v0.7: local first; explicit cloud opt-in only
aies.ai.default-provider=${AIES_AI_DEFAULT_PROVIDER:local}
aies.ai.cloud-enabled=${AIES_AI_CLOUD_ENABLED:false}
aies.ai.max-output-tokens=${AIES_AI_MAX_OUTPUT_TOKENS:512}
PROPERTIES
fi
cp "$SOURCE/scripts/v07-smoke.sh" scripts/v07-smoke.sh
chmod +x scripts/v07-smoke.sh
mkdir -p docs
cp "$SOURCE/docs/CATASTRO-v0.7.md" docs/CATASTRO-v0.7.md
printf 'Installed v0.7 overlay. Review git diff and run bash scripts/v07-smoke.sh\n'
