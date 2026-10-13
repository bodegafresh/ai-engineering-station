# AIES — F3.5: idempotencia durable y journal monotónico

## Base y alcance

Fuente revisada: `main` commit `393c5ab7c1cd2dac9ea3153546f7e8855c3dcd8f` (PR #45, F3.4). Este paquete es **un incremento de F3.5**, no una certificación de E2E ni de exactly-once general.

Se mantiene Java 21, Quarkus, `ExecutionWorkerEngine`, `ExecutionHandoffStore`, `CommunicationStore`, `WorkspaceDocumentationService`, SQLite y Vert.x Event Bus local. No se crea otro orquestador, consumidor o framework.

## Cambios de este incremento

1. `CommunicationStore.appendIdempotent(operationKey, kind, workflowId, taskId, payload)`: añade tabla de claves idempotentes en **la misma transacción SQLite** que el registro. Una repetición exacta retorna el mismo ID incluso después de reiniciar; una repetición conflictiva falla. `append()` sigue disponible para otros productores.
2. El worker y el documentador utilizan `execution-{attemptId}-artifact` para evitar duplicar registros cuando una caída ocurre entre `communication.appendIdempotent()` y `ExecutionHandoffStore.recordEffect(ARTIFACT_RECORDED)`.
3. `ExecutionHandoffStore.recordEffect()` admite solo `PREPARED -> FILE_WRITTEN -> ARTIFACT_RECORDED`, con replay idéntico y CAS en el avance. No permite saltos ni retrocesos.
4. `ExecutionIdempotencyTest` cubre replay tras reinicio, conflictos, orden de fases y persistencia.

## Limitaciones abiertas y riesgos P0

- No hay atomicidad entre filesystem, `communication.sqlite` y `execution-handoff.sqlite`. La idempotencia en el destino elimina duplicados del registro para el **mismo intento**, pero no impide archivos huérfanos, ni duplicados de trabajo entre intentos distintos.
- La exclusión entre solicitudes concurrentes del mismo intento **no está implementada**. El CAS de fase evita ciertas actualizaciones inconsistentes, pero dos llamadas simultáneas pueden invocar Ollama y competir por el mismo archivo. Requiere `claimEffect` con propietario, expiración y fencing, o single-flight por clave con recuperación durable.
- Si se cae después de publicar archivo pero antes de registrar `FILE_WRITTEN`, la recuperación actual rechaza el archivo no journalizado. Requiere staging/reconciliación con evidencia verificable, sin aceptar contenido no autorizado.
- `approveEvidence` es un método público del store y no valida por sí mismo el contenido del registro de auditoría. Debe limitarse su uso y centralizar el cierre en una frontera de verificación segura, con hash material comprobado inmediatamente antes del commit.
- El worker usa un modelo HTTP bloqueante; seguir usando `vertx.executeBlocking(..., false)` en los consumidores. No ejecutar SQLite, filesystem o `CompletableFuture.get()` sobre el event loop.
- La serialización JSON del payload debe mantenerse estable para la comparación SHA-256; versiones de contrato nuevas deben introducir versión de operación si cambian representación.

## Criterios para cerrar completamente F3.5

1. Concurrencia real: dos solicitudes idénticas del mismo intento no llaman dos veces al modelo ni publican dos archivos.
2. Inyección de crash en cada frontera: antes/después de archivo, registro de comunicación, journal, stageEvidence, audit y completion.
3. Validar la aprobación dentro de una frontera controlada y comprobar propiedad, decisión, evidencia y SHA-256.
4. Recuperar tras reinicio de Quarkus con SQLite y filesystem reales.
5. Reintentos con nuevo lease no pueden aprobar artefactos del lease anterior.
6. Tests Gradle completos y smoke E2E con Ollama simulado; no afirmar E2E antes de realizarlo.

## F4 — Primer E2E real y entrega final

1. **Preparación:** workspace temporal aislado con archivo Java pequeño, modelo Ollama simulado para CI y real para smoke local.
2. **Flujo:** intake -> requirements -> plan -> DAG -> aprobación de operador -> `ExecutionWorkerEngine` -> Event Bus -> documentador -> Markdown -> SHA-256 -> `ExecutionQualityGate` -> estado `COMPLETED`.
3. **Entrega:** manifiesto durable por workflow con task ID, attempt ID, artifact ID, ruta, hash, audit ID, decisión, timestamps, versión de modelo y limitaciones; no inventar evidencia.
4. **Pruebas negativas:** archivo modificado, autorización ausente, lease vencido, duplicación, auditoría rechazada, caída/reinicio, dependencias bloqueadas.
5. **Cierre:** documentar comandos, entorno, tiempos, resultados y fallos; ejecutar la suite completa. La primera demo no implica que AIES pueda ejecutar ingeniería arbitraria de manera autónoma.

## F5 y F6

- **F5 — Engineering Tools:** patch y verificación Docker bajo permisos explícitos, límites, aislamiento y evidencia. Nunca shell arbitrario ni trading real.
- **F6 — Operación:** métricas, trazas, backpressure, límites de concurrencia, recuperación, seguridad, pruebas de carga y compatibilidad Gradle 9. Retirar tests que dependan de `complete()` marcado `forRemoval` cuando exista alternativa comprobada.
- **Aprendizaje del especialista técnico:** registrar fallos y soluciones como casos versionados y probados en `TechnicalSpecialistGuidance`, no permitir que un LLM modifique políticas de autorización automáticamente.

## Instalación y verificación

```bash
./gradlew :apps:control-plane:test --tests '*ExecutionIdempotencyTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
git status --short
```

**Estado de validación:** código preparado a partir de las fuentes revisadas en GitHub; Gradle aún debe ejecutarse en el checkout local. Si falla, no abrir PR hasta corregirlo.

## F3.5.3 — Verified Completion Boundary (2026-10-13)

- `approveEvidence` (legacy) falla cerrado; `approveVerifiedEvidence` valida evidencia canónica y auditoría persistida antes de CAS a COMPLETED.
- `ExecutionQualityGate` conserva Event Bus y delega transición al store verificador.
- Tests de rechazo, aprobación exacta, replay y desbloqueo de DAG.
- F4.1: E2E reproducible con modelo simulado, falla/reinicio y reporte final.
- F4.2: Ollama local real con permisos explícitos y resultados materiales.
- F4.3: aceptación y observabilidad con evidencia de ejecución.
- Riesgo pendiente: SQLite y archivo físico no comparten transacción; revisar TOCTOU para amenazas locales.
