# AIES — Continuidad tras F3.3 (handoff documental autorizado, incremento inicial)

## Base y alcance

Base de implementación: `main` commit `a1d5a9e554fc010aaed684b1dcf5b3a5ad3e53bb` (PR #43). Este ZIP no sustituye módulos ni crea otro orquestador. Mantiene Java 21, Quarkus, SQLite, Vert.x Event Bus y los contratos de comunicación existentes.

**Estado:** F3.3 **parcial**. No declarar recuperación completa, E2E ni seguridad multitenant. Las pruebas deben ejecutarse en Fedora antes de merge.

## Cambios

1. `ExecutionCapabilityAuthorization`: registro inmutable de autorización documental con `workflowId`, `taskId`, capacidad, ruta Java relativa y SHA-256 del fuente.
2. `ExecutionHandoffStore`: tabla SQLite aditiva `execution_capability_authorizations`; autorización idempotente, conflicto fail-closed y consulta durable. La autorización solo se permite cuando la tarea está `PENDING` y asignada a `software-engineer`.
3. `ExecutionHandoffResource`: endpoint REST de operador `POST /api/v1/execution/workflows/{id}/tasks/{taskId}/authorize-documentation`, protegido por `WorkspaceOperatorAuthorizer` y los flags existentes. Body: `{"sourcePath":"src/main/java/example/A.java","sourceSha256":"<64 hex>"}`. No acepta `approved` desde el cliente.
4. `ExecutionWorkerEngine`: si existe autorización documental persistida, utiliza el consumidor `WorkspaceDocumentationEventBus.GENERATE` y su servicio existente; correlaciona workflow/task/attempt, verifica el registro canónico y **no crea un segundo artefacto**. Deja la tarea `EVIDENCE_PENDING` para el Quality Gate. Sin autorización, conserva el camino de ejecución preexistente por compatibilidad.
5. `WorkspaceDocumentationService`: compara el hash del archivo fuente con el hash aprobado antes de inferencia. Mantiene la verificación material F3.2.
6. Tests unitarios de contrato de autorización.

## Configuración / flujo opt-in

Requiere los flags existentes `aies.execution.handoff.enabled`, `aies.planning.enabled`, `aies.execution.worker.enabled`, `aies.execution.documentation.enabled`. Se recomienda un timeout de worker mayor al de documentación y un lease superior al timeout del worker, respetando las validaciones existentes. No habilitar en un workspace no confiable.

1. Importar plan previamente auditado vía REST existente.
2. Calcular SHA-256 de un fuente Java del workspace configurado y registrar autorización por endpoint autenticado.
3. Ejecutar `ExecutionWorkerEventBus.RUN_ONE` con el workflow; el worker reclama el intento durable y despacha el documentador mediante Event Bus.
4. El documentador genera archivo + artefacto; el worker verifica correlación y registra `EVIDENCE_PENDING`.
5. Invocar `ExecutionQualityEventBus.REVIEW` por el flujo de revisión existente. **No confundir evidencia pendiente con tarea aprobada.**

## Riesgos y limitaciones abiertos (bloqueantes antes de E2E productivo)

- **Autorización del bus:** `WorkspaceDocumentationEventBus.GENERATE` sigue aceptando `approved=true` como entrada interna. El endpoint REST es autenticado, pero no hay aislamiento/autenticación por consumidor en el Event Bus local. Migrar el consumidor a verificar autorización persistida por `workflowId/taskId/attemptId` y prohibir la invocación independiente no autorizada antes de uso no confiable.
- **No existe journal de efectos idempotente:** caída después de escribir el archivo pero antes de insertar el artefacto deja un archivo huérfano; reintento puede fallar con `FileAlreadyExistsException`. Implementar estados PREPARED/FILE_WRITTEN/ARTIFACT_RECORDED/STAGED, claves por intento y reconciliación. No borrar archivos huérfanos automáticamente.
- **No es fencing completo:** se verifica token al hacer `stageEvidence`, pero el LLM y el filesystem pueden haber producido efectos antes de detectar lease vencido. Validar lease antes de ejecutar y antes de publicar; usar escritura a staging y commit por token.
- **Auditoría no transaccional:** `approveEvidence()` sigue confiando en llamadores. Debe comprobar evidencia y auditoría dentro de una única frontera de aprobación; SQLite separadas requieren protocolo de reconciliación.
- **Rutas:** SHA-256 del fuente detecta cambios, pero hay carreras TOCTOU y posibles symlinks intermedios; endurecer con workspace aislado, `SecureDirectoryStream` donde sea viable y snapshots inmutables.
- **Compatibilidad:** tareas no autorizadas siguen ruta genérica existente. La autorización documental se limita a `software-engineer`/`software`; ampliar con registry de capacidades existentes y permisos por tarea, no con múltiples workers.
- **No se ejecutó Ollama real ni E2E:** los tests del paquete son unitarios. Las pruebas reales deben comprobar archivos, contenido, auditoría, DAG y reinicio.

## Siguiente incremento obligatorio: F3.4 — Journal + fencing + control del Event Bus

P0:
- Consumidor documental debe recuperar y verificar autorización persistida; eliminar confianza en booleanos externos.
- Tabla de intentos/efectos durable con clave `(workflowId,taskId,attemptId)` y fases idempotentes.
- Reconciliar archivo generado, artifact append, stage evidence y auditoría tras reinicio; rechazar duplicados y stale attempts.
- Revisión de lease y fencing antes y después de I/O, no solo al stage.
- Pruebas con HTTP Ollama simulado, SQLite temporal, caídas en cada fase y duplicados concurrentes.
- Migrar `approveEvidence()` para validar la auditoría y materialidad sin permitir bypass.

**Aceptación:** recuperación de caída entre filesystem y SQLite sin duplicados ni aprobación falsa; invocación documental no autorizada rechazada en el propio consumidor; ninguna tarea `COMPLETED` sin auditoría verificable.

## F4 — Primer E2E final

- Ingesta/requisitos/plan auditado -> autorización -> DAG -> agente -> Markdown real -> hash -> audit -> `COMPLETED` -> manifiesto durable de entrega.
- Pruebas de dos tareas con dependencia, auditoría rechazada, reinicio, pérdida de red del modelo, cambio de fuente y salida del workspace.
- Manifiesto con workflow, requisito, task, attempt, artifact id, ruta, SHA-256, audit record id, decisión, fechas y limitaciones. No inventar evidencias.

## F5 — Agente de ingeniería; F6 — Operación

- F5: adaptar herramientas de workspace/patch y verificación Docker ya presentes; aprobación antes de aplicar, límites de comandos y logs verificables.
- F6: métricas, tracing, backpressure, timeouts, pool de conexiones, límites de concurrencia y de recursos, pruebas de carga y seguridad.
- Aprendizajes verificados se agregan a `TechnicalSpecialistGuidance` mediante cambios versionados y tests, nunca auto-modificación de políticas por el LLM.

## Validación

```bash
./gradlew :apps:control-plane:test --tests '*ExecutionCapabilityAuthorizationTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
git status --short
```

No ejecutar operaciones SQLite, HTTP bloqueante ni `CompletableFuture.get()` directamente en el event loop. Los consumidores existentes emplean `vertx.executeBlocking(..., false)`; preservar ese patrón. Revisar la futura transición a `Future` composables y worker pools acotados, sin crear pools por solicitud.
