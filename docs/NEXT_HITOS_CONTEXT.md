# AIES — Continuidad después del Hito D

## Baseline
`bodegafresh/ai-engineering-station`, `main` commit `5c64fc23727331df67b5eec7493fce876bb4e766`, consultado 2026-10-13. Java 21, Quarkus, Vert.x Event Bus, SQLite. P1.3 DAG, handoff; P1.4 durable claim/fail.

## Hito D entregado — worker explícito, sin autonomía implícita

- `ExecutionWorkerEventBus.RUN_ONE`: `aies.execution.worker.run-one.v1`, opt-in, un intento por mensaje, sin REST nuevo.
- `ExecutionWorkerEngine`: claim durable del handoff existente, consulta plan inmutable, manda tarea al Event Bus `aies.execution.specialist.execute.v1`, exige respuesta `{executed:true,content:"..."}`, crea artefacto real en `CommunicationStore`, y registra estado `EVIDENCE_PENDING` en SQLite. No inventa evidencias ni marca COMPLETED.
- `ExecutionWorkerPolicy`: validación de respuestas, coherencia de asignación y timeout inferior al lease.
- `ExecutionHandoffStore`: métodos aditivos `plan` y `stageEvidence`. No altera tablas ni contratos existentes.
- Tests unitarios para validación de respuestas y límites del worker.

**Importante:** el Event Bus `aies.execution.specialist.execute.v1` es un puerto hacia un ejecutor especializado real. Este paquete NO registra un ejecutor de mentira ni llama a herramientas. Sin un adaptador real, el intento falla de forma acotada y reintenta. El worker NO es un E2E final. Los resultados no se consideran aprobados.

## Brechas prioritarias, Hito E

1. Implementar adaptador real al mecanismo de especialistas del proyecto, con validación de capacidades/herramientas, permisos, límites de tokens y timeouts. No usar `SpecialistEventBus.SELECT` como si ejecutara trabajo: solo selecciona.
2. Hacer idempotente la recepción de resultados por `(workflowId,taskId,attemptId)`: outbox/inbox transaccional, reconciliación de artefactos si el proceso cae entre `append` y `stageEvidence`. Hoy puede quedar un artefacto huérfano en ese intervalo.
3. `EvidenceVerifier`: verificar registro `ARTIFACT`, SHA-256, contenido, vínculo exacto workflow/task y procedencia. **No aceptar `artifact://...` por formato únicamente.**
4. Auditar artefacto por `CommunicationEventBus.AUDIT_REQUEST`, aplicar rúbrica según metodología, controlar revisión/bloqueo, y solo entonces transición `EVIDENCE_PENDING -> COMPLETED` con verificación atómica de referencia de auditoría. Prohibir `complete` directo sin gate cuando se habilite producción.
5. Propagar `DEAD` a tareas dependientes y definir estados finales de workflow. Añadir dead-letter para expiración de lease y retries.
6. Añadir pruebas de integración de Event Bus, worker real y almacenamiento con reinicio, timeout, replay, auditoría negativa y ejecución sin permisos.

## Hito F — E2E con entrega final

- Entrega versionada agregando artefactos aprobados, evidencias y resultados de aceptación.
- Escenario vertical pequeño: intake -> plan -> auditoría plan -> import -> especialista real -> artefacto -> verificación -> auditoría -> COMPLETE -> entrega final.
- E2E con proceso real Quarkus + SQLite + Event Bus, fallos inyectados, concurrencia, timeout y recuperación; una prueba con modelo real local aparte de CI determinista.
- Observabilidad: métricas por transición, latencia, reintentos, leases expirados, auditorías, costo estimado/real, correlación por workflow/task/attempt.

## Riesgos conocidos

- El puerto `EXECUTE` no está implementado por este paquete: ejecución depende de adaptador real posterior.
- `EVIDENCE_PENDING` bloquea DAG hasta auditoría; es intencional.
- Las operaciones sobre `execution-handoff.sqlite` y `communication.sqlite` no son atómicas entre sí.
- El `claim` existente no implementa dead-letter automático de leases vencidos al llegar a maxAttempts; requiere reconciliador.
- Una llamada al Event Bus que excede el lease puede generar efectos tardíos; cualquier adaptador debe ser idempotente.
- Sin compilación y suite completa en Fedora, no afirmar validación E2E.

## Comandos

```bash
./gradlew :apps:control-plane:test --tests '*ExecutionWorkerPolicyTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
```
