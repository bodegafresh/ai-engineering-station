# AIES — F3.4 Recovery & Security: continuidad

## Base, compatibilidad y estado

Base verificada por GitHub: `main` commit `9936c64ccbacc6c1e2e19b7cbf95b796c7b25d16` (PR #44). Este paquete es **un incremento F3.4, no el cierre de todas las garantías**. Mantiene Java 21, Quarkus, Vert.x local Event Bus, SQLite, `ExecutionWorkerEngine`, `ExecutionHandoffStore`, `WorkspaceDocumentationService`, y `ExecutionQualityGate`. No agrega frameworks, workers paralelos, REST interno ni Python al runtime.

## Implementado

1. **Autorización en consumidor:** `WorkspaceDocumentationService.generate` ya no confía en `approved=true`; requiere registro inmutable de autorización en SQLite y lease `LEASED` vigente para el `attemptId`, además del SHA-256 de la fuente. Una invocación directa por Event Bus sin esas condiciones falla antes de invocar al LLM.
2. **Fencing en puntos de efecto:** se revalida el lease antes del modelo, antes de escribir, y al registrar cada fase. Un token obsoleto no puede confirmar fases en SQLite. No se afirma aislamiento absoluto del filesystem ante carreras TOCTOU.
3. **Journal aditivo por intento:** tabla `execution_attempt_effects` con `(workflow_id, task_id, attempt_id)`, fases `PREPARED`, `FILE_WRITTEN`, `ARTIFACT_RECORDED`, archivo y hash. Se mantiene una única base SQLite de ejecución.
4. **Reconciliación acotada:** si el mismo intento activo tiene `FILE_WRITTEN` y el archivo coincide con su ruta/hash, se recupera el artefacto sin volver a llamar al modelo. Si está `ARTIFACT_RECORDED`, se verifica archivo y artefacto y se devuelve el mismo resultado. Archivos sin journal o alterados se rechazan, no se borran.
5. **Tests:** rechazo de ejecución sin grant/lease, correlación de autorización, fencing después de `fail`, journal durable tras reabrir SQLite.

## Limitaciones abiertas que impiden declarar E2E resiliente

- **Ventana entre append en `communication.sqlite` y registro `ARTIFACT_RECORDED` en `execution-handoff.sqlite`:** un crash puede producir un artefacto duplicado en el reintento. Solución siguiente: clave idempotente única `(workflowId,taskId,attemptId,kind)` en el store canónico, o outbox con deduplicación transaccional en destino. No basta con `INSERT OR IGNORE` en el journal.
- **Carreras de filesystem:** validaciones `toRealPath` y symlinks no eliminan TOCTOU; aislar workspace, usar snapshot de fuente y staging por intento. La escritura actual del archivo precede la validación final del lease; si vence en medio del I/O, puede quedar un archivo huérfano, aunque no se aprueba.
- **Concurrencia del mismo intento:** dos solicitudes idénticas simultáneas pueden invocar el modelo; falta exclusión/claim de fase con CAS y límites por workspace. `executeBlocking(...,false)` evita bloquear event loop, pero no garantiza serialización global.
- **Calidad y auditoría:** `ExecutionHandoffStore.approveEvidence` aún confía en el caller. Restringir a una frontera de aprobación que verifique audit record y evidencia material; no exponer aprobación directa a otros consumidores.
- **Reintentos entre leases:** una vez expirado el intento anterior, el siguiente puede producir otro documento con otro `attemptId`. Debe decidirse semántica de compensación/no borrado y auditoría de artefactos huérfanos.
- **Modelo externo:** faltan pruebas HTTP Ollama simulado y reales, reinicio de Quarkus, fallos inyectados en cada fase y pruebas de concurrencia. No se afirma E2E validado.

## F3.5 — Cierre de idempotencia y calidad (P0, antes de F4)

1. Revisar `CommunicationStore` e incorporar operación `appendIdempotent` con clave única y validación de payload conflictivo. Mantener compatibilidad con registros previos.
2. Crear journal/outbox con transiciones CAS y correlación; cubrir crash antes/después de archivo, artefacto, staging, auditoría y `COMPLETED`.
3. Crear snapshot de fuente autorizada; escritura temporal aislada con comprobación de lease justo antes de publicación y reconciliación explícita. No borrar artefactos de otros intentos.
4. Endurecer aprobación: verificar en una única frontera lógica la propiedad de `workflowId/taskId`, `audit.subjectId`, decisión, hash material y transición; registrar intento/audit ID en entrega.
5. Test de dos invocaciones concurrentes, stale attempt, recuperación tras reinicio, hash alterado, duplicación de mensaje y timeout de modelo; asegurar que ninguna tarea `COMPLETED` carece de auditoría real.

## F4 — Primer E2E con entrega final

- Solicitud -> requisitos -> plan auditado -> DAG -> autorización -> Event Bus -> documentador Ollama -> Markdown -> evidencia SHA-256 -> auditoría -> `COMPLETED` -> manifiesto final durable.
- Incluir dos tareas con dependencia; no desbloquear segunda si primera queda `EVIDENCE_PENDING` o audit rechaza.
- Manifest con workflow, task, attempt, artifact id, ruta, hash, audit id, decisión, fecha, estado y limitaciones. No inventar éxito ni evidencias.
- Tests E2E con modelo HTTP simulado para CI; prueba manual con Ollama real, workspace temporal y configuración opt-in.

## F5 / F6

- **F5:** integrar herramientas de ingeniería existentes (patch y verificación Docker) bajo capacidades aprobadas, límites y evidencia; no comandos arbitrarios ni ejecución financiera real.
- **F6:** métricas, trazas, límites de concurrencia y recursos, backpressure, recuperación, seguridad y pruebas de carga. Evaluar Gradle 9 y eliminar usos de API `complete()` obsoleta.
- Aprendizajes del especialista técnico deben ser cambios versionados y testeados en `TechnicalSpecialistGuidance`, nunca autoadaptación de políticas por el LLM.

## Ejecución y aceptación

```bash
./gradlew :apps:control-plane:test --tests '*ExecutionRecoverySecurityTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
git status --short
```

El Event Bus debe seguir usando `localConsumer` y `vertx.executeBlocking(..., false)` para SQLite, HTTP bloqueante y filesystem; jamás bloquear event loops. El journal SQLite es fuente de verdad y no se sustituye por mensajes volátiles. Este incremento no habilita automáticamente las capacidades en `application.properties`.
