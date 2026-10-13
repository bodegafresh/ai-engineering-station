# AIES — Continuidad posterior a Hito E (P1.5, primer incremento)

Baseline: main commit `6e613ae01ee1cc6d5ab303aab5ee8e620c4bc511` (PR #38).

## Implementado

- Verificador de evidencia: `artifact://communication/<id>`; lectura del registro inmutable en `CommunicationStore`, SHA-256 validado por el store, correspondencia workflow/task/payload y `kind=execution-result`.
- `ExecutionQualityGate`: consulta al auditor existente por Vert.x Event Bus, valida el registro de auditoría persistido y solamente entonces mueve `EVIDENCE_PENDING` a `COMPLETED` mediante CAS en SQLite.
- `ExecutionQualityEventBus`: comando interno `aies.execution.quality.review.v1`, opt-in, deshabilitado por defecto.
- Revisión rechazada permanece `EVIDENCE_PENDING`; no se inventa resultado ni se fuerza éxito.

## Limitaciones importantes (no afirmar E2E)

1. La auditoría estructural actual no verifica que código compile, que una fuente externa sea cierta ni que un test se haya ejecutado. Antes de producción, implementar verificadores por metodología y pruebas reproducibles reales; no equiparar APPROVED con validez material.
2. `ExecutionHandoffStore.complete()` legado aún puede pasar de LEASED a COMPLETED por referencia; debe restringirse o migrarse sin romper consumidores existentes, y añadir tests de bypass.
3. `approveEvidence` valida identificador de auditoría, pero la relación se verifica en `ExecutionQualityGate`; la API de almacenamiento es pública y exige encapsular la autorización antes de habilitar consumidores no confiables.
4. El worker usa dirección `aies.execution.specialist.execute.v1`, pero no hay adaptador de ejecución real. Integrar capacidades existentes sin ejecutar herramientas arbitrarias.
5. `communication.sqlite` y `execution-handoff.sqlite` son independientes; reconciliación e idempotencia transaccional quedan pendientes. Evitar duplicados de artefactos tras crash.
6. `EVIDENCE_PENDING` con auditoría REJECTED/REVISION_REQUIRED no tiene flujo de revisión ni máximo de iteraciones gestionado por el handoff. Diseñar estados terminales, revisión y trazabilidad.
7. Leases expirados y tareas `DEAD` requieren propagación de bloqueo y métricas; asegurar claims multiinstancia y fencing.
8. Falta seguridad/identidad del operador para disparar workflows completos y registrar aprobación humana.

## Siguiente Hito E2 — Adaptador real y verificación específica

- Conectar el Event Bus del worker a especialistas reales existentes, sin segundo catálogo.
- Evidencia específica por tipo: documentación con fuentes, código con ejecución de tests en sandbox, análisis con datos de origen; registrar provenance y hashes.
- Contratos de respuesta con `attemptId` y fencing, reintentos y límites de costo; no permitir ejecución peligrosa implícita.
- Test de integración: plan importado -> worker -> resultado real -> artifact -> gate -> COMPLETED; escenarios de rechazo y timeout.

## Hito F — Entrega final y E2E

- Agregador durable por workflow: todas las tareas COMPLETED y auditorías aprobadas, o estado parcial/terminal explícito.
- Resultado final persistido y consultable, trazabilidad requisito->tarea->artefacto->auditoría.
- Pruebas de crash/replay, dependencia, timeout, reintento, multiinstancia, permisos y evidencia manipulada.
- Observabilidad: métricas de leases, latencias, costo, revisiones, fallos, y correlación workflow/task/attempt.

## Validación obligatoria

`./gradlew :apps:control-plane:test --tests '*ExecutionEvidenceVerifierTest' --no-daemon --console=plain`

`./gradlew clean test --no-daemon --console=plain`

No se ejecutó Gradle en el entorno de generación del ZIP. Habilitación de calidad únicamente después de completar pruebas de integración y revisar riesgos anteriores.
