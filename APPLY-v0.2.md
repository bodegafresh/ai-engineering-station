# Parche v0.2 — AI Engineering Station

Este ZIP **contiene solamente archivos modificados o nuevos**, con rutas relativas a la raíz del repositorio. Conserva el Gradle wrapper y los archivos de usuario.

```bash
cd ~/git/ai-engineering-station
git status --short
# Crear rama antes de aplicar el parche
git switch -c feat/durable-orchestrator-v02
unzip -o ~/Descargas/ai-engineering-station-v0.2-patch.zip -d .
./gradlew clean test
./gradlew :apps:control-plane:quarkusDev
```

No se han ejecutado las pruebas Gradle en el entorno de creación del ZIP. **No hagas commit hasta validar en Fedora.**

Se conservan los datos SQLite existentes. Haz copia de `data/` antes de probar si contiene información valiosa.

El procesamiento de tareas continúa simulado; no hay todavía inferencia de IA.
