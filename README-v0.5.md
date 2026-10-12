# AIES v0.5 — parche incremental: Tool Gateway + propuesta local asistida

## 0. Antes de aplicar
Partir de v0.4 con `git status` limpio, en una rama nueva. Este ZIP no contiene el proyecto completo; conserva todos los archivos existentes. No sobrescribe el runner v0.4.

```bash
cd ~/git/ai-engineering-station
git switch main && git pull --ff-only
git switch -c feat/tool-gateway-v05
unzip -o ~/Descargas/ai-engineering-station-v0.5-gateway.zip -d .
```

## 1. Configuración
Agrega al final de `apps/control-plane/src/main/resources/application.properties`:

```properties
aies.tools.enabled=${AIES_TOOLS_ENABLED:false}
aies.tools.token=${AIES_TOOLS_TOKEN:}
aies.tools.workspace=${AIES_WORKSPACE:.}
aies.tools.python=${AIES_TOOLS_PYTHON:.aies/venv/bin/python}
```

El parche NO modifica `application.properties` automáticamente, para evitar sobrescribir tu configuración local.

Genera un token nuevo y expórtalo SOLO en la terminal (no lo subas a Git):

```bash
export AIES_TOOLS_TOKEN="$(openssl rand -hex 32)"
export AIES_TOOLS_ENABLED=true
export AIES_WORKSPACE="$PWD"
export AIES_TOOLS_PYTHON="$PWD/.aies/venv/bin/python"
export AIES_MODEL_BASE_URL=http://127.0.0.1:11434
export AIES_MODEL_NAME=qwen2.5-coder:7b
export AIES_MODEL_ENABLED=true
export AIES_MODEL_TIMEOUT_SECONDS=90
export AIES_SCHEDULER_LEASE_SECONDS=180
./gradlew clean test --no-daemon
./gradlew :apps:control-plane:quarkusDev --console=plain
```

**Importante:** exporta el mismo token en la terminal donde hagas curl (por ejemplo, con un archivo privado local `source .aies/session.env`, permiso 600). No compartas el token en capturas.

## 2. Página local de prueba
En otra terminal:

```bash
mkdir -p /tmp/aies-browser-test
printf '<html><head><title>AIES v0.5 UX</title></head><body><h1>Prueba UX</h1><button>Enviar</button><img src="data:," /></body></html>' > /tmp/aies-browser-test/index.html
python3 -m http.server 8765 --bind 127.0.0.1 --directory /tmp/aies-browser-test
```

## 3. Prueba de políticas y gateway
Desde otra terminal, con token y workspace exportados:

```bash
.aies/venv/bin/python -m pytest tests/test_agent_v05.py tests/test_policy.py -v
bash scripts/v05-smoke.sh
```

Esperado: 401 sin token, 403 sin aprobación, 400 para shell.exec, SVG y PNG generados y JSON `ok:true`.

## 4. Prueba de IA proponiendo herramienta (NO la ejecuta)

```bash
AIES_WORKSPACE="$PWD" .aies/venv/bin/python tools/aies_agent.py propose \
  'Inspecciona la interfaz web local y revisa accesibilidad'
cat .aies/proposal-v05.json
```

Revisa manualmente el JSON. Si estás conforme:

```bash
AIES_WORKSPACE="$PWD" .aies/venv/bin/python tools/aies_agent.py execute --approve
```

Prueba alternativa:

```bash
AIES_WORKSPACE="$PWD" .aies/venv/bin/python tools/aies_agent.py propose \
  'Genera un diagrama SVG de la arquitectura actual'
cat .aies/proposal-v05.json
AIES_WORKSPACE="$PWD" .aies/venv/bin/python tools/aies_agent.py execute --approve
```

El modelo solo propone. **No se habilita un bucle autónomo de ejecución**.

## 5. Regresión y Git

```bash
./gradlew clean build --no-daemon
.aies/venv/bin/python -m pytest tests/ -v
git diff --check
git status --short
# Asegúrate de ignorar .aies/, .env y __pycache__.
git add apps/control-plane/src/main/java/dev/aies/control/ToolGatewayResource.java \
  tools/aies_agent.py tests/test_agent_v05.py scripts/v05-smoke.sh \
  docs/CATASTRO-v0.5.md README-v0.5.md \
  apps/control-plane/src/main/resources/application.properties
git diff --cached --check
git commit -m 'feat(gateway): add approved local tool execution and agent proposals'
git push -u origin feat/tool-gateway-v05
```

Haz merge solo después de comprobar que los tests, el smoke y la prueba asistida funcionan.

## Diagnóstico
- 403 `tool gateway disabled`: variable `AIES_TOOLS_ENABLED` ausente.
- 401: token no coincide entre terminales o es demasiado corto.
- 422: la herramienta falló; comprueba PlantUML, Playwright, paths y servidor 8765.
- 500: revisa ruta de Python y los logs Quarkus.
- La propuesta de Qwen puede ser JSON inválido; eso se rechaza deliberadamente, no se ejecuta.
