# AIES — continuidad después de F2 (workspace documentation)

Baseline inspeccionado: `main` commit `301bde7216e37261719a6b28652550d9fc83bc5c`, merge PR #40.

## Arquitectura y objetivo

AIES conserva intake universal, metodología, planificación, DAG durable, especialistas existentes, Event Bus interno, comunicación canónica, auditoría y quality gate. F2 añade **una operación explícita, opt-in y aprobada por operador** para documentar un archivo Java real. No crea un segundo catálogo, ni un framework de agentes, ni un ejecutor de shell. `software-engineer/software` reutiliza el ID/capacidad del catálogo existente.

## F2 entregado

- `WorkspaceDocumentationService`: valida `workflowId`, `taskId`, `attemptId`, aprobación explícita, ruta relativa, archivo Java regular dentro del workspace, no symlinks, tamaño máximo y límite de prompt. Lee el contenido real, calcula SHA-256, solicita inferencia mediante `OllamaSpecialistExecutor`, verifica correlación de intento, escribe Markdown en `.aies/generated-docs/` mediante archivo temporal y movimiento atómico, calcula SHA-256 de salida y crea un artefacto canónico `execution-result` en `CommunicationStore`.
- `WorkspaceDocumentationEventBus`: dirección `aies.execution.documentation.generate.v1`, consumidor local opt-in, procesamiento bloqueante delegado por Vert.x; REST no expuesto.
- Tests: rechazos por aprobación, path traversal, ruta absoluta, archivo no Java, symlink; petición HTTP real a un servidor de prueba, archivo material y artefacto durable.
- Configuración por defecto deshabilitada: `AIES_EXECUTION_DOCUMENTATION_ENABLED=false`, `AIES_EXECUTION_DOCUMENTATION_WORKSPACE_ROOT=./projects`, `AIES_EXECUTION_DOCUMENTATION_TIMEOUT_SECONDS=90`.

**Límite fundamental:** F2 NO enlaza automáticamente la operación documental con `ExecutionWorkerEngine.runOne`, ni ejecuta el Quality Gate, ni finaliza tareas. La operación requiere solicitud interna explícita con `approved=true`; no equivale a autenticación del llamante, por lo que se debe restringir el emisor del Event Bus a código de operador autorizado. No habilitar con entrada de usuario sin autorización real. El SHA-256 demuestra integridad de bytes, no exactitud semántica del documento. La persistencia del archivo y la base de datos no es una transacción atómica; el replay puede encontrar un archivo ya creado y requiere reconciliación. El documento creado no es un test de código ejecutado.

## Contrato interno para F2

Solicitud a `aies.execution.documentation.generate.v1`:

```json
{"workflowId":"wf-1","taskId":"task-1","attemptId":"attempt-1","approved":true,"sourcePath":"src/main/java/example/A.java"}
```

Respuesta: `status=EVIDENCE_PENDING`, `documentPath`, `documentSha256`, `sourcePath`, `sourceSha256`, `artifactRecordId`, `verification=FILE_HASH_VERIFIED_NOT_AUDITED`. No implica `COMPLETED`.

## Aprendizajes para definiciones de especialistas

**Especialista técnico (`software-engineer`)**: añadir en su definición de prompts/competencias que (1) diferencie inspección real del repositorio de inferencia textual; (2) cite rutas relativas y hashes reales aportados por herramientas; (3) no afirme tests ejecutados sin logs/exit codes; (4) separe hallazgos observados de suposiciones; (5) produzca documentación de API pública, dependencias, limitaciones y pasos de verificación; (6) no invente comandos ni resultados. Mantener estas instrucciones como políticas versionadas del especialista, no como memoria libre ni cambio automático de capacidades. No cambiar `SpecialistCatalog` sin revisión de capacidades y pruebas.

## F3 — seguridad, correlación y recuperación (bloqueante para E2E autónomo)

1. Enlazar el worker y la herramienta de documentación por un adaptador de capacidades del catálogo existente; trasladar `sourcePath` y autorización a un contrato durable explícito, sin extraer rutas del objetivo con LLM.
2. Validar `attemptId` en `ExecutionWorkerEngine` antes de aceptar respuesta y añadir pruebas de intento tardío, duplicado y lease vencido.
3. Cerrar `ExecutionHandoffStore.complete()` para impedir `COMPLETED` con URL o referencia no auditada; migrar consumidores con compatibilidad explícita.
4. Vincular `auditRecordId` de manera durable al estado aprobado; exigir validación material del archivo y del hash antes de auditar, además del registro canónico.
5. Introducir reconciliación idempotente para archivos/artefactos huérfanos entre filesystem, `communication.sqlite` y `execution-handoff.sqlite`; registrar intentos y estado terminal.
6. Reforzar autorización real de la operación: token/identidad de operador, controles por workspace y evitar que el flag `approved` sea autoafirmado por el modelo.
7. Seguridad de filesystem: comprobar hardlinks, cambios concurrentes de rutas y symlinks; usar mecanismos de acceso seguro o workspace aislado cuando se amplíe la superficie. Añadir límites de volumen y limpieza.

## F4 — entrega final y E2E

- Manifiesto durable de workflow con estados `COMPLETED`, `PARTIAL`, `BLOCKED`; trazabilidad requisito→tarea→archivo/hash→artefacto→auditoría; consulta REST autorizada.
- E2E automático con servidor HTTP simulado: intake→plan auditado→worker→herramienta real→archivo→verificación material→auditoría→manifiesto; E2E opt-in con Ollama local real.
- Pruebas de caída entre escritura y registro, reintento, dependencia DAG, auditoría rechazada y recuperación.

## F5 — agente de software con herramientas

Herramientas allowlist, workspace aislado, revisión de cambios, aprobación humana, test runner en contenedor sin red, evidencia de comandos reales/exit codes, rollback y límites de costo. Nunca dar acceso arbitrario a shell por prompt.

## Verificación local

```bash
./gradlew :apps:control-plane:test --tests '*WorkspaceDocumentationServiceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

No considerar F2 E2E completo hasta implementar y demostrar F3 y F4.

### Política incorporada en código

`TechnicalSpecialistGuidance.VERSION=f2.1` centraliza instrucciones revisables para `software-engineer`, aplicadas al prompt del documentador. Es una política explícita y versionada, **no** aprendizaje automático ni modificación silenciosa del catálogo de especialistas.
