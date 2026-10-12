# AI Engineering Station v0.7 — Hybrid Inference Overlay

Esta entrega es un **overlay incremental**, no un repositorio completo. Conserva las versiones v0.1–v0.6 y reemplaza únicamente `LocalModelClient.java`, además de agregar configuración y documentación.

## Instalación segura

```bash
cd ~/git/ai-engineering-station
git status --short                    # debe estar limpio
git switch -c feat/hybrid-inference-v07
unzip ~/Descargas/aies-v0.7.zip -d /tmp/aies-v07
bash /tmp/aies-v07/scripts/install-v07.sh "$PWD"
git diff --check
git diff
bash scripts/v07-smoke.sh
```

## Prueba local (sin costos)

```bash
export AIES_AI_DEFAULT_PROVIDER=local
export AIES_AI_CLOUD_ENABLED=false
export AIES_MODEL_ENABLED=true
# conserva tus variables AIES_MODEL_BASE_URL y AIES_MODEL_NAME existentes
./gradlew :apps:control-plane:quarkusDev --console=plain
```

## Prueba de nube (puede tener costo)

```bash
export AIES_AI_DEFAULT_PROVIDER=anthropic
export AIES_AI_CLOUD_ENABLED=true
read -rsp 'ANTHROPIC_API_KEY: ' ANTHROPIC_API_KEY; echo
export ANTHROPIC_API_KEY
# configura AIES_ANTHROPIC_MODEL con un modelo vigente en tu cuenta
./gradlew :apps:control-plane:quarkusDev --console=plain
```

El enrutamiento v0.7 es **por configuración del operador**, no todavía por agente ni por objetivo. No implementa routing automático multiagente, caché semántica ni medición real de costos: son los siguientes hitos, documentados en `docs/CATASTRO-v0.7.md`.
