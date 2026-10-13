# AIES — Continuidad tras Hito F1: inferencia real con modelo local

**Baseline inspeccionado:** `main` SHA `3913c77a28688005efa933becff220f1406c9e87` (PR #39, Hito E).

## Alcance entregado

- `RealSpecialistEventBus`: consumidor opt-in de `ExecutionWorkerEngine.EXECUTE` (`aies.execution.specialist.execute.v1`). No se altera el worker, catálogo, SQLite, contratos ni auditor.
- `OllamaSpecialistExecutor`: llama realmente a Ollama local `POST /api/generate` con HTTP Java, tiempo limitado, máximo 1200 tokens solicitados, sin herramientas ni acceso a archivos; devuelve `executed=true` **solo después de respuesta real** y `verification=UNVERIFIED_MODEL_OUTPUT`.
- `RealSpecialistPolicy`: permite exclusivamente IDs/capacidades existentes en `SpecialistEventBus`: `researcher/research`, `requirements-auditor/requirements|audit`, `software-engineer/software|implementation`, `experiment-analyst/statistics|experiments`. Valida inputs y limita tamaño. No inventa otro catálogo.
- Tests: contrato, entradas inválidas, URL local y petición HTTP real contra servidor de prueba en localhost (sin dependencia de Ollama).
- No se habilita automáticamente. Sin escrituras en proyectos, ejecución de comandos, navegador, ni herramientas arbitrarias.

## Activación local controlada

Con Ollama ejecutándose y el modelo descargado, configurar en `.env`/entorno, **no secretos en Git**:

```
AIES_EXECUTION_REAL_SPECIALIST_ENABLED=true
AIES_EXECUTION_WORKER_ENABLED=true
AIES_EXECUTION_QUALITY_ENABLED=true
AIES_MODEL_BASE_URL=http://127.0.0.1:11434
AIES_MODEL_NAME=qwen3:4b
AIES_EXECUTION_REAL_SPECIALIST_TIMEOUT_SECONDS=25
AIES_EXECUTION_WORKER_TIMEOUT_SECONDS=30
AIES_EXECUTION_LEASE_SECONDS=60
```

**Importante:** para un modelo lento, ajustar `real-specialist.timeout-seconds < worker.timeout-seconds < lease-seconds`. Los valores por defecto son deliberadamente acotados y pueden ser insuficientes para un primer calentamiento de modelo. Asegurar que las variables se traducen correctamente a propiedades MicroProfile en la configuración efectiva.

## Pruebas de aceptación locales

1. `./gradlew :apps:control-plane:test --tests '*RealSpecialistPolicyTest' --tests '*OllamaSpecialistExecutorTest' --no-daemon --console=plain`
2. `./gradlew clean test --no-daemon --console=plain`
3. `git diff --check`; inspeccionar archivos nuevos y backups no versionados.
4. Validación operativa separada: importar un plan ya auditado con tarea `researcher/research`, activar consumidor y worker, enviar `aies.execution.worker.run-one.v1` desde una prueba/invocador autorizado; comprobar respuesta real y artefacto `execution-result` en `communication.sqlite`.
5. Solicitar `aies.execution.quality.review.v1`; verificar estado `COMPLETED` **solo si auditoría aprobada**. El contenido de LLM sigue sin verificación material.

## Riesgos críticos y deuda para F2/F3

1. **E2E todavía no demostrado:** no existe prueba automática completa `intake → plan → worker → modelo real → artefacto → gate → entrega`. El test HTTP valida inferencia, no el workflow.
2. **Revisión material:** auditoría estructural no comprueba corrección técnica ni existencia de fuentes; no afirmar que el modelo leyó el repositorio. Para documentación técnica, integrar lectura de workspace autorizada y evidencia de archivos/hash, con verificador específico.
3. **Bypass heredado:** `ExecutionHandoffStore.complete()` aún permite completar con solo una referencia; restringirlo y agregar test de regresión antes de activar automatización no supervisada.
4. **Aprobación a nivel store:** `approveEvidence` recibe un `auditRecordId` pero no lo persiste; la relación se verifica en `ExecutionQualityGate` solamente. Añadir journal durable de aprobaciones.
5. **Crash/replay:** `communication.sqlite` y `execution-handoff.sqlite` separados; un crash después de append y antes de stage genera artefactos huérfanos. Diseñar reconciliación idempotente por `workflowId/taskId/attemptId` y fencing.
6. **Salida del modelo:** el worker actualmente no valida `attemptId` devuelto por el adaptador. Añadir comprobación explícita y test de respuesta tardía/duplicada.
7. **Especialistas reales:** el adaptador ejecuta inferencia local, no un agente con herramientas. Para ingeniería de software implementar workspace aislado, lectura/escritura acotada, aprobación de acciones, test runner sandbox y captura de evidencias reales. No prometer ejecución de código a partir de generación de texto.
8. **Final delivery:** manifiesto inmutable con referencias verificadas a artefactos y auditorías, estados `COMPLETED/PARTIAL/BLOCKED`, consulta REST autorizada y pruebas de reinicio.
9. **Resiliencia:** multiinstancia SQLite, locks, expiración de leases, propagación de `DEAD`, costos/tokens, métricas y límites de concurrencia.
10. **Seguridad:** el adaptador limita URL a loopback y no permite herramientas, pero una máquina con Ollama expuesto o redireccionamientos HTTP debe ser endurecida; definir políticas de acceso y aprobación por operador.

## Próximos incrementos

- **F2 — Vertical slice documentador verificable:** lectura explícitamente autorizada de archivo Java dentro del workspace, hashes, prompts con contexto real, escritura controlada de Markdown, test de contenido y procedencia. Mantener `CommunicationStore` como registro canónico.
- **F3 — Recovery & audit hardening:** fencing de intento, outbox/inbox o reconciliación, bloqueo de bypass, vínculo durable de auditoría y reintentos con backoff.
- **F4 — Final delivery + E2E:** manifiesto, REST de consulta, integración real con Ollama local y prueba E2E automatizada con fake HTTP + prueba opt-in contra modelo real. Escenarios de timeout, reinicio, rechazo, duplicación y dependencia DAG.
- **F5 — Software agent con herramientas:** workspace sandbox, herramientas allowlist, permisos explícitos, ejecución de tests real, evidencia de comandos/exit codes y rollback seguro.

**Definición de listo para E2E con resultado final:** un workflow aprobado ejecuta trabajo real, persiste artefactos, demuestra sus criterios de aceptación, supera el quality gate, produce un manifiesto verificable y se recupera de fallos sin duplicar efectos. F1 por sí solo NO satisface esa definición.
