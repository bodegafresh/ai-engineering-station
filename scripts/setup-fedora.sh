#!/usr/bin/env bash
set -euo pipefail
sudo dnf install -y python3 python3-pip java-21-openjdk-headless plantuml
python3 -m venv .aies/venv
.aies/venv/bin/python -m pip install --upgrade pip
.aies/venv/bin/python -m pip install 'playwright>=1.50,<2'
.aies/venv/bin/python -m playwright install chromium
printf '\nInstalado. Si Chromium falla por bibliotecas faltantes, ejecuta: .aies/venv/bin/python -m playwright install-deps chromium\n'
