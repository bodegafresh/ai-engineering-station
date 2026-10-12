# v0.12 — Puente de selección a ejecución (opt-in)

Base: `main` después de v0.11. Esta entrega **no** implementa ejecución multiagente.

## Incluye
- `SpecialistPromptComposer`: selecciona un especialista por el router, carga perfil Markdown y renderiza su template Velocity.
- Fallback a `planner` si el router no encuentra señales.
- `POST /api/v1/execution/preview`: inspección de selección y prompt sin llamar al modelo.
- `AIES_SPECIALIST_EXECUTION_ENABLED=false` por defecto: preserva ejecución v0.11.
- Con la opción habilitada, **una sola inferencia** utiliza el prompt del especialista elegido; no se ejecutan reviewers.
- Tests de selección, fallback, validación y contenido Velocity no interpretado.

## Instalación
Desde una rama creada sobre `main`, extraer ZIP y ejecutar:

```bash
bash /tmp/aies-v012-execution-bridge/scripts/install-v012.sh "$PWD"
./gradlew clean test --no-daemon --console=plain
bash scripts/v07-smoke.sh
git diff --check
```

## API de previsualización

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/execution/preview \
  -H 'Content-Type: application/json' \
  -d '{"objective":"Investigar mercado de colegios en comunas de Chile"}' | python3 -m json.tool
```

## Ejecución opt-in
Con inferencia local operativa:

```bash
export AIES_MODEL_ENABLED=true
export AIES_SPECIALIST_EXECUTION_ENABLED=true
./gradlew :apps:control-plane:quarkusDev --console=plain
```

Enviar tareas a `/api/v1/tasks` como en v0.11. El campo `model` en el resultado sigue representando al proveedor/modelo, no al especialista. Para auditar la selección, usar `/api/v1/execution/preview` con el mismo objetivo.

## Límites y seguridad
- Sin llamadas cloud nuevas ni herramientas automáticas.
- La selección es heurística; no garantiza el especialista óptimo.
- Los reviewers listados **no** se ejecutan.
- El preview devuelve el prompt completo: mantener el servidor solo en loopback y no exponerlo sin autenticación.
- No hay contexto del repositorio ni búsqueda web automática; los perfiles no sustituyen evidencia.
- No hay ejecución de código, cambios Git ni aprendizaje persistente en este incremento.

## Próximos incrementos
v0.13: plan multiagente persistente y checkpoints; v0.14: revisores reales y quality gates; v0.15: herramientas aisladas, worktrees y approvals; v0.16: memoria por proyecto y observabilidad; v0.17: conectores de investigación con fuentes verificables y presupuestos.
