#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m unittest discover -s tests -p 'test_worktree_v06.py' -v
./gradlew test --no-daemon
printf '\nPASS: v0.6 unit tests and Gradle tests\n'
