# AIES — Continuidad después de F3.2 (material evidence slice)

Base de referencia: `main` commit `b4c1c4558d9424525bdfefa27f844bdf486c0aa0` (PR #42). No se ha ejecutado Gradle contra el checkout del repositorio en el entorno de empaquetado.

## Implementado en este incremento

- `WorkspaceDocumentationService` registra en `Artifact.sourceRefs` una referencia versionada `workspace-doc-sha256:<relative-path>:<sha256>`; mantiene el contrato y el `CommunicationStore` existentes.
- `WorkspaceMaterialEvidence` verifica ruta relativa bajo `.aies/generated-docs`, archivo regular, ausencia de symlink directo, tamaño, SHA-256 y coincidencia exacta con el contenido canónico.
- `ExecutionQualityGate` verifica material antes y después de la auditoría cuando el artefacto contiene esta referencia. El Event Bus usa la raíz de workspace configurada. El constructor antiguo permanece por compatibilidad y falla cerrado cuando recibe referencias materiales sin raíz.
- Tests de alteración, archivo eliminado, symlink y path traversal.

## Limitaciones que impiden declarar F3.2 o E2E completamente cerrados

1. **Sin integración DAG documental**: `WorkspaceDocumentationEventBus.GENERATE` sigue separado de `ExecutionWorkerEngine.EXECUTE`. La aprobación del operador es un booleano dentro del mensaje, no un registro autorizado persistido; no conectar automáticamente hasta resolverlo.
2. **Sin idempotencia entre SQLite y filesystem**: `WorkspaceDocumentationService` escribe el archivo antes del registro en `CommunicationStore`; una caída deja un archivo huérfano. Diseñar journal/outbox con clave (`workflowId`, `taskId`, `attemptId`) y reconciliación. No borrar archivos no reconocidos automáticamente.
3. **Bypass de `approveEvidence`**: sigue público y confía en el llamador. Mover la validación del registro de auditoría y de evidencia al límite transaccional antes de declarar seguridad frente a todos los consumidores.
4. **TOCTOU**: la verificación de filesystem no elimina carreras con cambios externos entre hash y aprobación; workspace debe ser inmutable/aislado durante revisión y usar políticas de no-follow de todos los componentes de ruta.
5. **Compatibilidad**: artefactos anteriores sin `workspace-doc-sha256:` conservan su verificación canónica, no pasan por verificación de archivo. Antes de exigir evidencia material globalmente, definir migración y tipado de artefactos.
6. **No es una prueba con Ollama real**: los tests son locales y deterministas. E2E con inferencia real queda pendiente.

## Próximo incremento: F3.3 — Durable capability handoff

- Definir `ExecutionResult` versionado para múltiples capacidades, sin segundo worker ni catálogo.
- Vincular un `sourcePath` autorizado y aprobación humana persistida a la tarea importada, nunca inferida por el LLM ni aceptada de un booleano no autenticado.
- Adaptar `ExecutionWorkerEngine` para delegar al documentador existente a través del Event Bus, con fencing por intento y validación de correlación.
- Persistir intent journal e idempotency key antes de efectos; reconciliar `file written`, `artifact appended`, `evidence staged`, `audit approved` tras reinicio.
- Bloquear confirmación de intentos vencidos y duplicados. Pruebas de caída/reinicio y concurrencia.

## F4 — Final Delivery + E2E

- Manifiesto durable de workflow: estados COMPLETED/PARTIAL/BLOCKED, DAG, entregables, hashes, auditorías, trazabilidad a requisitos.
- E2E documental real con Ollama HTTP simulado, más prueba optativa con Ollama real. Ejecutar desde intake/plan aprobado hasta archivo verificable, auditoría, finalización y entrega.
- Tests de rechazo de auditoría, reinicio entre persistencias, tareas dependientes y duplicados.

## F5 — Engineering tools; F6 — Operación

- F5: reutilizar workspace changes, verificación Docker y herramientas autorizadas para generar parches, correr tests y capturar logs/exit codes. Aprobar antes de aplicar cambios, sin comandos arbitrarios.
- F6: métricas, tracing, control de recursos, límites de concurrencia, seguridad del workspace, resiliencia y pruebas de carga.
- Aprendizajes de implementación (`Path` import, `JsonObject.remove`, SQLite JDBC, compilación de tests) deben quedar en `TechnicalSpecialistGuidance` mediante revisiones versionadas y tests, no autogenerar reglas productivas.

## Validación de aceptación

```bash
./gradlew :apps:control-plane:test --tests '*WorkspaceMaterialEvidenceTest' --tests '*WorkspaceDocumentationServiceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

**Estado honesto**: este es un incremento F3.2 de evidencia material, no el cierre de F3.2 de recuperación completa ni un E2E final.
