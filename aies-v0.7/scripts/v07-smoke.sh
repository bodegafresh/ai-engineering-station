#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :apps:control-plane:compileJava :apps:control-plane:test --no-daemon
python3 -m unittest discover -s tests -p 'test_worktree_v06.py' -v
printf '\nPASS: compilation, Quarkus tests and v0.6 worktree tests.\n'
printf 'NOTE: Cloud providers need opt-in and credentials; no cloud calls were made.\n'
