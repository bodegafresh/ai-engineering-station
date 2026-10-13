# AIES — Continuidad tras F3 (execution integrity slice)

**Base verificada en GitHub:** `main` commit `e0feca25773a7b8ce60508000f8f657cac81b39e` (PR #41). **No se ejecutó Gradle en el entorno de empaquetado**: la aceptación depende de `clean test` y pruebas de integración en Fedora.

## Cambios de F3 entregados

1. `ExecutionWorkerEngine` rechaza respuestas sin correlación exacta `workflowId`, `taskId`, `attemptId` (lease token) antes de crear artefactos.
2. `ExecutionHandoffStore.complete()` heredado falla cerrado. No permite completar con URLs o referencias no auditadas.
3. Migración SQLite aditiva `audit_record_id` en `execution_tasks`, persistido en la misma actualización que marca `COMPLETED`; consulta `approvedAuditId` para manifiestos futuros.
4. Test existente de DAG actualizado para usar `stageEvidence` → `approveEvidence`, demostrando que una dependencia no se libera mientras espera aprobación.

## Límites explícitos: F3 todavía NO está cerrado completamente

- `approveEvidence` sigue siendo un método público del store; **el llamador debe validar la auditoría**. `ExecutionQualityGate` sí valida la identidad y decisión del registro, pero aún falta hacer inaccesible la transición desde consumidores no autorizados (p. ej. encapsular mediante servicio de dominio o exigir prueba verificable dentro de la misma transacción).
- `ExecutionWorkerEngine` aún escribe el artefacto en `communication.sqlite` antes de `stageEvidence` en otra base. Una caída entre ambas deja artefactos huérfanos; falta inbox/outbox/reconciliación durable y claves de idempotencia por intento.
- `WorkspaceDocumentationService` aún opera por comando explícito separado del DAG. Integración del capability adapter y autorización de operador **pendientes**.
- `ExecutionQualityGate` verifica contenido canónico, no todavía el archivo material y SHA-256 de F2. Falta verificación de filesystem antes de auditoría.
- Fencing de lease en filesystem, reinicio durante ejecución, pruebas con duplicados y concurrencia multiinstancia aún pendientes.
- `ExecutionWorkerEngine` captura fallos de correlación y los trata como retryable; evaluar clasificación permanente y métrica de seguridad.

## F3.2 — Cierre del circuito durable (siguiente prioridad)

1. Definir un `ExecutionResult` versionado para todos los especialistas: identidad de intento, artefactos, hashes, capacidad y estado `UNVERIFIED`; adaptarlo al Event Bus existente sin otro catálogo.
2. Persistir journal de intento (`workflowId`,`taskId`,`attemptId`) y reconciliar respuestas duplicadas / artefactos huérfanos con reglas deterministas.
3. Introducir capability adapter entre worker y documentación F2, con `sourcePath` persistido y aprobación de operador no autoafirmada; jamás inferir rutas con LLM.
4. Verificar SHA-256 del archivo material y del origen antes de aprobar; denegar archivos inexistentes, mutados o symlinks.
5. Pruebas de caída después de escritura, replay idempotente, lease vencido, intento ajeno, auditoría rechazada y dependencia bloqueada.
6. Revisar la API pública de `approveEvidence` para impedir bypass de calidad desde cualquier caller.

## F4 — Primer E2E y entrega final

- Crear manifiesto durable por workflow con `COMPLETED`, `PARTIAL`, `BLOCKED`, y trazabilidad requisito → tarea → archivo/hash → artifactId → auditRecordId.
- Ejecutar caso documental vertical: intake → plan aprobado → claim → ejecución autorizada en workspace → archivo real → validación hash → auditoría → manifiesto. Probar con Ollama HTTP simulado y opcionalmente Ollama real.
- Incluir reinicio entre persistencias, repetición de mensajes, dependencias DAG y salida con auditoría negativa. No reportar E2E completo antes de estas pruebas.

## F5 — Software agent tools

- Reutilizar mecanismos existentes de workspace changes y Docker verification; comandos allowlist, aprobación humana, red deshabilitada, límites, rollback y evidencia real de exit code y logs.
- Mantener reglas técnicas versionadas en `TechnicalSpecialistGuidance`: distinguir resultados observados de inferencias, no afirmar pruebas no ejecutadas; agregar pruebas de regresión para conflictos `Path`, `JsonObject.remove`, SQLite JDBC y validaciones de contratos. No autoeditar catálogo de capacidades.

## Validación y operación

```bash
./gradlew :apps:control-plane:test --tests '*ExecutionHandoffStoreTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

**Nota de compatibilidad:** cualquier consumidor del método heredado `complete()` deberá migrar a la transición auditada. El cambio es intencionalmente fail-closed y puede afectar tests o integraciones externas que usaban esa vía insegura.
