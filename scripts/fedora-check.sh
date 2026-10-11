#!/usr/bin/env bash
set -u
printf '=== Fedora ===\n'; cat /etc/fedora-release 2>/dev/null || true
printf '=== CPU ===\n'; lscpu | grep -E 'Model name|CPU\(s\)|Thread|Core' || true
printf '=== RAM ===\n'; free -h
printf '=== GPU ===\n'; lspci | grep -Ei 'VGA|Display|3D' || true
printf '=== Java ===\n'; java -version 2>&1 || true
printf '=== Vulkan ===\n'; command -v vulkaninfo >/dev/null && vulkaninfo --summary || true
printf '=== Podman ===\n'; podman --version 2>/dev/null || true
