#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v curl >/dev/null || { echo "Falta curl" >&2; exit 1; }
command -v unzip >/dev/null || { echo "Falta unzip" >&2; exit 1; }
version=8.14.3
cache="${XDG_CACHE_HOME:-$HOME/.cache}/ai-engineering-station/gradle"
mkdir -p "$cache"
archive="$cache/gradle-${version}-bin.zip"
if [[ ! -f "$archive" ]]; then
  curl --fail --location --retry 3 "https://services.gradle.org/distributions/gradle-${version}-bin.zip" -o "$archive"
fi
if [[ ! -x "$cache/gradle-${version}/bin/gradle" ]]; then
  unzip -q -o "$archive" -d "$cache"
fi
"$cache/gradle-${version}/bin/gradle" wrapper --gradle-version "$version" --distribution-type bin
printf 'Wrapper oficial generado. Ahora ejecuta ./gradlew test
'
