# AIES — roadmap canónico y aprendizajes (F4–F6)

Este archivo es la **única fuente versionada** para próximos hitos y aprendizajes. Los archivos `docs/NEXT_HITOS_CONTEXT_*.md` se retiran del árbol activo; su historial permanece en Git. No se modifica ninguna otra documentación.

## Arquitectura y reglas permanentes
- Java 21, Quarkus, SOLID, arquitectura limpia y adaptadores separados.
- Vert.x Event Bus para comunicación interna, REST solo en la frontera del operador.
- Nunca bloquear event loop con JDBC, filesystem, `Future.get()` o inferencia: despachar esas operaciones mediante worker / `executeBlocking` apropiadamente.
- SQLite durable, contratos inmutables, correlación de workflow/task/attempt, idempotencia, CAS y reintentos acotados.
- `COMPLETED` requiere evidencia canónica y auditoría aprobada; jamás aceptar solo una respuesta del modelo como prueba.
- Sin Python en el runtime Java; instaladores Bash, fail-closed, sin sobreescribir tests preexistentes.
- Herramientas de ingeniería requieren autorización explícita; nunca trading real.
- Aprendizajes de especialistas: casos verificables versionados, nunca cambios automáticos de autorización por LLM.

## F4.1 — Integración determinista (cerrado según main)
Prueba de DAG de dos tareas, Event Bus local, SQLite real, correlación, evidencia, auditoría exacta, replay y reapertura. No equivale a E2E HTTP o modelo real.

## F4.2 — Recuperación del worker (este incremento)
### Cambios
- La respuesta de `ExecutionWorkerEngine` refleja `DEAD` cuando SQLite agota los intentos, en vez de afirmar siempre `RETRY_PENDING`.
- Si el `fail` no actualiza el lease (expiración/carrera), se retorna `RECONCILIATION_REQUIRED` y no se afirma un retry durable.
- Tests deterministas para `executed=false`, correlación incorrecta, agotamiento de reintentos, reinicio de store y DAG bloqueado.
- No se rebajan las verificaciones `executed=true`, correlación ni auditoría.

### Validación
```bash
./gradlew :apps:control-plane:test --tests '*F42ExecutionRecoveryTest' --no-daemon --console=plain
./gradlew :apps:control-plane:test --tests '*F41DeterministicWorkflowE2ETest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Riesgos no cerrados
- Un timeout puede ser ambiguo: el especialista pudo producir un efecto externo. No reintentar automáticamente capacidades con efectos materiales sin reconciliación durable y fencing.
- El catch de `runOne` puede ocultar fallas secundarias del store; mejorar diagnósticos seguros y métricas en F4.3.
- SQLite y filesystem no son una única transacción. Falta crash injection en todas las fronteras.
- Los tests de este hito no demuestran concurrencia multiproceso ni resistencia a saturación del event loop.

## F4.3 — E2E de aceptación operativa (en progreso)
### Entregado en F4.3-A: manifiesto de aceptación canónico
- `ExecutionAcceptanceManifest` reconstruye una vista de solo lectura desde `execution-handoff.sqlite` y `communication.sqlite`.
- Cada tarea expone estado, intentos, ID y SHA-256 del artefacto canónico, auditoría aprobada, SHA-256 de auditoría y marcas temporales disponibles.
- `VERIFIED` exige todas las tareas en `COMPLETED`, con evidencia canónica y auditoría aprobada exacta; cualquier otra situación queda `INCOMPLETE` o falla cerrada.
- Test de persistencia/reapertura y transición antes/después de aprobación.
- La inspección NO modifica estados, NO ejecuta modelos y NO equivale a una transacción atómica entre ambas bases.
- El manifiesto todavía NO verifica el hash actual de archivos del workspace ni registra versión del modelo; esas ausencias se declaran como limitaciones, no se inventan.

### F4.3-B: pendiente para cierre de aceptación real
1. Exponer el manifiesto mediante un endpoint de operador autenticado, con `@Blocking`, paginación/límites y sin revelar secretos.
2. CLI Bash reproducible con preflight Ollama/Quarkus, tokens válidos, workspace descartable y chequeos de disponibilidad.
3. Ejecutar camino real intake → plan → aprobación → especialista → archivo → SHA-256 material → auditoría → manifiesto; registrar IDs, latencias, versión real del modelo y resultados.
4. Negativos obligatorios: autorización ausente, SHA alterado, lease vencido, respuesta tardía, auditoría bloqueada, replay y reinicio.
5. Añadir consistencia de lectura entre bases, o documentar explícitamente la naturaleza eventual del snapshot; nunca prometer atomicidad inexistente.
6. CI: suite completa, smoke de operador reproducible y evidencia real adjunta al PR. No declarar F4.3 cerrado sin ejecutar el smoke.

### Comandos de validación F4.3-A
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionAcceptanceManifestTest' --no-daemon --console=plain
./gradlew :apps:control-plane:test --tests '*F42ExecutionRecoveryTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F5 — Herramientas controladas
- Parches de código y verificación aislada con permisos explícitos, límites de CPU/memoria/tiempo, allowlist de rutas y comandos, Docker reproducible.
- Restringir salida de datos, paths, secretos y acceso a red. Auditar cada efecto.
- Aplicar capacidad solo al especialista técnico asignado y con evidencia de prueba.

## F6 — Operación y endurecimiento
- Métricas y trazas de Event Bus, cola, worker, SQLite, modelo y auditoría.
- Backpressure, fairness, límites de concurrencia y carga.
- Recuperación ante crashes y procesos concurrentes; pruebas de seguridad y threat model.
- Compatibilidad Gradle 9 y eliminación controlada de APIs `forRemoval`.
- Runbooks, rollback, CI y criterios finales de aceptación.

## Aprendizajes registrados
1. `DriverManager` puede fallar bajo classloaders Quarkus; usar apertura JDBC compatible con la configuración validada.
2. `List.of().contains(null)` puede lanzar NPE; preferir validación de nulos segura.
3. Una respuesta del especialista debe declarar `executed=true` además de la correlación correcta.
4. Los tests E2E deben verificar el estado durable, no solo el JSON de respuesta.
5. Los instaladores no deben usar patches corruptos, rutas duplicadas ni sobrescribir archivos existentes sin revisión.
6. Conservar un solo roadmap canónico; el historial Git preserva los incrementos anteriores.

## F4.3-B — Incremento de frontera operativa (pendiente de validación local)
- Nuevo endpoint GET `/api/v1/execution/workflows/{id}/acceptance`, `@Blocking`, deshabilitado por defecto mediante `AIES_EXECUTION_ACCEPTANCE_ENABLED=false`; autorización por `X-AIES-Workspace-Token` usando `WorkspaceOperatorAuthorizer` y feature flag de handoff.
- Respuesta de solo lectura desde `ExecutionAcceptanceManifest`; workflow desconocido responde 404. No se invoca modelo, no se conceden capacidades, no se modifica DAG.
- Test Quarkus de rechazo anónimo y token inválido; Bash `scripts/f43b-smoke.sh` de operador que inspecciona un workflow existente y valida esquema si `jq` está disponible.
- **NO CERRADO**: este incremento no constituye E2E real con Ollama ni comprueba material actual; el smoke es read-only. No marcar F4.3 como aceptado sin pruebas adicionales.

### Validación requerida
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionAcceptanceResourceTest' --tests '*ExecutionAcceptanceManifestTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Siguiente incremento obligatorio: F4.3-C — E2E real, evidencias y cierre
1. Preflight de modelo Ollama, Quarkus, flags y token sin imprimir secretos; workspace temporal aislado.
2. Orquestar intake → plan → aprobación → claim → especialista real por Vert.x Event Bus → evidencia → auditoría → aceptación, sin atajos ni autorizaciones automáticas.
3. Medir correlación, latencia, modelo real y material SHA-256; verificar bytes actuales y reabrir SQLite.
4. Negativos: grant ausente, SHA cambiado, auditoría rechazada, lease vencido, replay, timeout y respuesta tardía. Ninguno desbloquea DAG.
5. Probar que JDBC, I/O y HTTP del modelo no bloquean event loop, con límites de concurrencia y timeouts.
6. Documentar resultados realmente ejecutados y artefactos adjuntos al PR. Sin pruebas reales, declarar estado PENDIENTE.

### Aprendizaje F4.3-B
No confundir manifiesto canónico con prueba material o resultado externo. Separar pruebas deterministas, smoke HTTP y E2E con modelo real. Instalador Bash idempotente por rechazo, sin sobreescribir archivos existentes.

## F4.3-C — Material acceptance (incremento implementado; E2E real pendiente)
### Implementado
- `ExecutionMaterialAcceptance`: lectura estricta e independiente del manifiesto canónico, usando los verificadores de evidencia existentes.
- `MATERIAL_VERIFIED` exige aceptación canónica, al menos una referencia material por tarea y coincidencia actual del contenido y SHA-256 de los archivos.
- Si falta una referencia material, se informa `INCOMPLETE`/`NOT_APPLICABLE`, sin reinterpretar el `VERIFIED` canónico como prueba material.
- Si el archivo cambió, la inspección falla cerrada (`SecurityException`); no muta el DAG ni invoca modelos.
- Tests para ausencia de evidencia material y detección de alteración tras aprobación.
- Preflight Bash de Ollama local sin imprimir tokens. No se introduce Python ni se cambia el contrato del Event Bus.

### Validación local requerida
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionMaterialAcceptanceTest' --tests '*ExecutionAcceptanceManifestTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
bash scripts/f43c-preflight.sh
git diff --check
```

### Pendiente para cerrar F4.3-C
1. Conectar inspección material a un endpoint operador `@Blocking`, autorizado y con workspace root configurado explícitamente; NO exponer el filesystem a rutas del usuario.
2. E2E real con Ollama: intake, plan aprobado, claim, modelo por Event Bus, evidencia, auditoría, aceptación; conservar IDs, latencias y versión efectiva del modelo.
3. Inyección de fallas: respuesta tardía, lease expirado, replay, reinicio, archivo alterado y rechazo de auditoría.
4. Evitar reintentos automáticos de invocaciones ambiguas; reconciliación durable de efectos externos.
5. Instrumentar event loop y workers; prohibir JDBC, archivos y HTTP bloqueantes en event loop.
6. Guardar evidencia real del smoke en el PR. No declarar E2E exitoso sin ejecución observada.

### Próximos hitos
- **F5**: herramientas de ingeniería autorizadas y aisladas; allowlist, recursos acotados, auditoría de efectos y pruebas reproducibles.
- **F6**: observabilidad, backpressure, recuperación multiproceso, threat model, runbooks y compatibilidad Gradle 9.

### Aprendizaje F4.3-C
La aceptación canónica no garantiza que los bytes actuales del workspace coincidan. Mantener niveles de verificación explícitos, sin cambiar semánticas existentes ni inventar resultados de pruebas.

## F4.3-D — Frontera de aceptación material (implementación propuesta; validación local pendiente)
### Alcance de este incremento
- GET `/api/v1/execution/workflows/{id}/material-acceptance`, operador autenticado mediante `WorkspaceOperatorAuthorizer`, `@Blocking`, solo lectura.
- Flag independiente `AIES_EXECUTION_MATERIAL_ACCEPTANCE_ENABLED=false` por defecto; también exige handoff habilitado. Root de workspace y root de datos provienen solo de configuración del operador.
- Reutiliza `ExecutionMaterialAcceptance` y su comprobación de contenido SHA-256; no introduce una segunda fuente de verdad, no modifica SQLite y no invoca especialistas.
- Fallas de autenticación/flag: 404. Alteración material detectada: 409 sin revelar paths. No equivale a snapshot atómico ni a garantía de integridad futura.
- Smoke Bash read-only de endpoint; no muestra token ni contenido de los documentos.

### Validación requerida antes del PR
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionMaterialAcceptanceResourceTest' --tests '*ExecutionMaterialAcceptanceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
# Con Quarkus activo, flags y token de operador configurados:
AIES_SMOKE_WORKFLOW_ID=<id-existente> bash scripts/f43d-smoke.sh
```
**No declarar E2E real con Ollama completado:** este incremento únicamente publica inspección material y smoke HTTP de lectura.

### Siguiente incremento F4.3-E — Ejecución real trazable
1. Preflight y prueba E2E real aislada con Ollama local y un workflow aprobado por operador, sin permisos implícitos ni scripts de shell ejecutados por el agente.
2. Verificar claim, correlación de attempt, respuesta del especialista por Event Bus, persistencia del artefacto y auditoría exacta; comprobar estado tras reapertura SQLite.
3. Para resultados materiales, usar únicamente capacidad `workspace-documentation` explícitamente autorizada; comparar bytes SHA-256 antes y después, y registrar ID de modelo y latencias observadas (nunca inventadas).
4. Probar negativos: ausencia de grant, lease vencido, replay, timeout ambiguo, auditoría BLOCKED y material alterado; ninguno puede liberar tareas dependientes.
5. Registrar límites de concurrencia, uso de workers y no bloqueo del event loop; separar evidencia determinista de evidencia con Ollama real.
6. Adjuntar reporte reproducible al PR. Solo entonces decidir cierre F4.3 y avance a F5.

### Aprendizaje F4.3-D
La aceptación material es una inspección puntual de bytes; exponerla no convierte un test unitario en ejecución real. Reutilizar la política existente y proteger la frontera REST evita divergencias y privilegios adicionales.

## F4.3-E — Corrección de correlación y smoke de modelo real (validación local pendiente)
- Corregida la respuesta de `OllamaSpecialistExecutor`: conserva `workflowId`, `taskId`, `attemptId` exigidos por `ExecutionWorkerEngine`. Antes el adaptador devolvía solo `attemptId`, haciendo fallar la correlación de toda respuesta real.
- Test de integración con HTTP local determinista + Vert.x Event Bus + SQLite: comprueba staging durable y que no se marca `COMPLETED` sin auditoría. **El servidor HTTP de este test NO es Ollama.**
- Smoke opt-in `F43EOllamaLiveTest` con Ollama local real: solo habilitado por `AIES_F43E_LIVE=true`; mide latencia observada y valida staging de evidencia. No fabrica auditoría ni afirma aceptación final.
- No hay grants de escritura, herramientas, shell ni aprobación implícita. No se alteran los contratos de Event Bus ni el flujo de auditoría.

### Validación obligatoria
```bash
./gradlew :apps:control-plane:test --tests '*F43ERealSpecialistIntegrationTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
bash scripts/f43e-live.sh # Ollama instalado, modelo descargado; opt-in real
```

### Riesgos y siguiente paso antes de cerrar F4
1. El smoke real verifica **inferencia y staging**, no intake HTTP → aprobación humana → auditoría semántica real → material SHA-256 → aceptación. No etiquetar como E2E completo.
2. Completar harness de aceptación operator-approved sobre APIs reales y `ExecutionQualityGate` sin fabricar decisiones de auditoría; registrar modelo, tiempos, correlaciones, audit IDs y SHA-256.
3. Reconciliar timeout ambiguo de inferencia y prevenir duplicados de invocaciones externas; actualmente el worker puede reintentar la inferencia de texto tras un timeout.
4. Asegurar límite de concurrencia y backpressure en `RealSpecialistEventBus`, con prueba de saturación del worker pool.
5. Verificar lease fencing, respuestas tardías, rechazo de auditoría, alteración material y reapertura SQLite en el camino real.
6. Solo tras esas pruebas cerrar F4.3 y avanzar a F5; mantener F6 para observabilidad y endurecimiento.

### Aprendizaje F4.3-E
Un adaptador real debe respetar exactamente el contrato de correlación del worker. Los mocks pueden ocultar un campo omitido. Mantener pruebas de contrato cruzadas adapter→Event Bus→worker y diferenciar evidencia de inferencia de evidencia auditada.

## F4.3-F — Admission control del especialista real (validación local pendiente)
- `SpecialistAdmissionGate`: semáforo justo, no bloqueante, capacidad 1..128 y lease `AutoCloseable` con liberación idempotente.
- `RealSpecialistEventBus`: admisión antes de `executeBlocking`; rechaza inmediatamente con código Event Bus 429 cuando no hay capacidad; HTTP bloqueante continúa fuera del event loop.
- Configuración `aies.execution.real-specialist.max-concurrent` (variable `AIES_EXECUTION_REAL_SPECIALIST_MAX_CONCURRENT`), por defecto 2. El flag de ejecución real sigue apagado por defecto.
- No hay cola oculta, no se invocan modelos al rechazar y no se altera el contrato de correlación.
- Limitación: límite por proceso JVM, no distribuido; la respuesta 429 llega al `ExecutionWorkerEngine` como error y su política durable de reintentos continúa aplicando. No se garantiza fairness entre procesos ni cancelación de inferencias que excedan el timeout del caller.

### Verificación obligatoria
```bash
./gradlew :apps:control-plane:test --tests '*SpecialistAdmissionGateTest' --tests '*F43ERealSpecialistIntegrationTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### F4.3-G — Cierre de aceptación E2E real (siguiente)
1. Harness opt-in de operador para workflow aprobado: intake → plan → ejecución → auditoría real → aceptación, sin auditorías fabricadas.
2. Correlación y observabilidad de `workflowId/taskId/attemptId`, modelo efectivo, tiempos y artefactos SHA-256; evitar secretos en logs.
3. Casos negativos con Ollama real y fixture determinista: saturación, timeout ambiguo, lease vencido, replay, auditoría rechazada y material alterado.
4. Evidencia de persistencia tras reinicio y chequeo de que ningún DAG avanza sin auditoría exacta.
5. Medir latencia de event loop y tamaño de pool; demostrar que JDBC/HTTP/archivos no corren en el event loop.
6. Adjuntar resultados observados al PR. Si no se ejecuta el E2E real, mantener F4 abierto.

### Aprendizaje F4.3-F
Un worker pool no constituye control de admisión: es necesario limitar las inferencias **antes** de encolarlas. Evitar esperas y `Semaphore.acquire()` en el event loop. Mantener el límite explícito y verificable.

### Después de F4
- **F5**: herramientas de ingeniería autorizadas, sandbox, límites de recursos y auditoría de efectos.
- **F6**: operación, métricas, resiliencia multiproceso, seguridad, runbooks y deuda Gradle.

## F4.3-G1 — Integración de auditoría y aceptación (validación local pendiente)
- Prueba determinista `F43GQualityGateIntegrationTest`: HTTP fixture local → adaptador real de transporte → Vert.x Event Bus → worker → SQLite → `ExecutionQualityGate` → manifiesto.
- Demuestra que auditoría BLOCKED mantiene el DAG detenido, que aprobación vinculada al artefacto habilita la dependencia, y que SQLite reabierto conserva COMPLETED.
- **Limitación fundamental:** el servidor HTTP y las decisiones de auditoría son fixtures del test; NO son Ollama ni auditoría semántica real. `VERIFIED` solo significa consistencia canónica.
- No modifica contratos productivos, permisos, stores ni flags. Sin Python, shell de agente ni trading real.

### Validación
```bash
./gradlew :apps:control-plane:test --tests '*F43GQualityGateIntegrationTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### F4.3-G2 obligatorio para cerrar F4
1. Harness opt-in con Ollama real, aprobación explícita del operador y auditoría real, sin respuestas ni decisiones fabricadas.
2. Recolección de IDs, tiempos, modelo efectivo, SHA-256 y persistencia tras reinicio; reporte versionado sin secretos.
3. Negativos: timeout ambiguo, respuesta tardía, lease vencido, saturación, rechazo, replay, material alterado.
4. Demostrar no bloqueo de event loop, límites de recursos y no duplicación de efectos; sin esto F4 sigue abierto.

## F4.3-G2 — Ollama live semantic audit (opt-in; local validation required)
- `F43G2OllamaAuditLiveTest`: real Ollama inference through Vert.x Event Bus, SQLite evidence staging, independent `SemanticAuditPolicy.review` using real Ollama, immutable audit record, `ExecutionQualityGate`, manifest and SQLite reopen.
- Does **not** fabricate approval: model may approve or block; both outcomes are checked. Semantic failure fails closed. No project write grants, shell or Python.
- `scripts/f43g2-live.sh`: loopback preflight and explicit opt-in; prints observed timings/IDs only after execution. Not run as part of ordinary `clean test`.
- Important: the test wires the actual semantic policy behind a test-local Event Bus consumer; it is NOT a Quarkus deployment with the production `CommunicationEventBus`, HTTP intake or human operator approval. It does not validate material filesystem bytes.

### Local validation
```bash
./gradlew :apps:control-plane:test --tests '*F43G2OllamaAuditLiveTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
bash scripts/f43g2-live.sh # requires local Ollama and downloaded model
```

### F4.3-G3 — Final operator E2E / F4 closure (next)
1. Quarkus opt-in harness: actual HTTP intake, operator authorization and approval, plan, worker and production Event Bus consumers.
2. Real semantic audit through production `CommunicationEventBus` with consistent data-root; capture audit ID and actual decision, no bypass.
3. Verify durable claim, lease fencing, timeout ambiguity, retries, late responses and concurrent admission 429; no duplicate material effects.
4. Explicit material grant and SHA-256 current-byte check when material writes are involved; reject tampering.
5. Capture model identity, latency and event-loop/worker metrics without secrets; persist reproducible report in PR.
6. Only close F4 after a successful real operator E2E. Then F5 tools and F6 operational hardening.

### Lesson F4.3-G2
Never equate real model inference with an operator-approved E2E. Semantic decisions must be persisted, matched to exact immutable artifact and rechecked by the quality gate; a BLOCKED result is valid evidence of fail-closed behavior.

## F4.3-G3 — Timeout / late reply fencing (incremento; F4 aún abierto)
- Test `F43G3TimeoutRecoveryTest`: consumidor Vert.x real que responde tarde; el worker usa un timeout acotado, agota su intento, persiste DEAD en SQLite y no libera dependencias; tras la respuesta tardía el DAG permanece bloqueado y el manifiesto INCOMPLETE.
- Sin mocks de auditoría, grants, scripts ejecutados por agentes, cambios de producción ni Python.
- No representa el E2E operador real: no ejecuta Quarkus HTTP intake, Ollama ni auditoría semántica productiva. No declarar F4 cerrado.

### Validación
```bash
./gradlew :apps:control-plane:test --tests '*F43G3TimeoutRecoveryTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### F4.3-G4 — Próximo incremento obligatorio
1. Harness Quarkus con entrada HTTP y aprobación explícita del operador, usando consumidores de producción y rutas SQLite consistentes.
2. Ejecutar modelo y auditoría semántica reales opt-in, guardar IDs, decisiones y tiempos observados, nunca inferidos.
3. Pruebas de lease vencido, saturación 429, replay, concurrencia y material alterado; confirmar no duplicación de efectos.
4. Verificar bytes SHA-256 actuales para grants materiales, aislamiento de workspace y ausencia de secretos en logs.
5. Adjuntar evidencia real de ejecución al PR; F4 se cierra únicamente al superar el E2E operativo completo.

### Aprendizaje
El timeout del request Vert.x no cancela necesariamente la inferencia externa. El fencing del lease y el estado durable deben impedir que una respuesta tardía desbloquee el DAG. DEAD requiere intervención o reconciliación explícita; no se debe marcar como COMPLETED.

## F4.3-G4 — Operator acceptance smoke (read-only)
- `scripts/f43g4-operator-acceptance.sh` uses production Quarkus HTTP operator endpoints (`tasks` and `acceptance`), checks invalid-token rejection, reads real SQLite-backed state via existing resources, and validates exact task ID/status consistency. It does not modify workflows, fabricate audits or trigger models.
- Requires an **existing** approved/imported workflow and explicit operator token. Only loopback HTTP accepted; temporary responses have 0700 directory permissions and are removed on exit; no token printed.
- Execution: `AIES_WORKFLOW_ID=... AIES_WORKSPACE_TOKEN=... bash scripts/f43g4-operator-acceptance.sh`. Optional `AIES_BASE_URL=http://127.0.0.1:8080`.
- Limitations: two REST calls are not an atomic snapshot. `VERIFIED` is canonical audit linkage, not real-world correctness. The smoke does not prove intake, plan approval, real Ollama, worker, recovery or material SHA current bytes.
- F4 **remains open** until the actual end-to-end operator-approved flow (intake → plan → approval → real worker/consumer → Ollama → material if authorized → real audit → acceptance) has been run, recorded and checked against negative cases. Do not replace this requirement with a mocked audit.
- F4.3-G5 next: orchestrated opt-in Quarkus E2E with production consumers, explicit human authorization, actual model identity/latency evidence, negative security/recovery cases and repeatable metrics; close F4 only on observed results. Then F5 tools and F6 hardening.
- Learning: a read-only HTTP acceptance check is valuable operational evidence but must never be labeled a complete E2E; distinguish canonical record hashes from live filesystem SHA-256.

## F4.3-G5 — Operator-controlled execution and audit commands (validation pending)
- New opt-in `ExecutionOperatorActionsResource`: authenticated operator REST POST `run-one` and POST `review`; dispatches ONLY through production Vert.x Event Bus consumers, never constructs its own worker/auditor. All flags default off; `@Blocking` avoids blocking the event loop while waiting for replies.
- Existing plan must already be approved and imported. No auto-approval or capability grants. Audit decision is the real result from production consumer; rejected audit does not unlock DAG.
- `scripts/f43g5-operator-e2e.sh`: loopback-only, explicit operator token, bounded iterations, uses production HTTP actions and acceptance manifest; fails if final status is not VERIFIED. `jq` and `curl` required; it never prints token. Outputs identifiers and canonical hashes but does not establish current material bytes.
- Risks: timeout is ambiguous; after HTTP 503/409 operator must inspect durable state before retrying. Script is not a substitute for negative tests, approval-path verification, production Ollama metrics, restart testing or material SHA checks.
- This is an **operator E2E slice**, NOT a complete intake-to-delivery certification. F4 remains open pending reproducible intake → audited plan → import → execution → real Ollama → real semantic audit → acceptance, negative cases and measured runtime evidence.

### Validate G5
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionOperatorActionsResourceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
bash -n scripts/f43g5-operator-e2e.sh
git diff --check
# Run against opt-in Quarkus with real Ollama, approved imported workflow and configured token:
AIES_WORKFLOW_ID=... AIES_WORKSPACE_TOKEN=... bash scripts/f43g5-operator-e2e.sh
```

### Next: F4.3-G6 — Full operator intake and recovery certification
- Automate real intake and explicit operator approval path using existing REST contracts; do not manufacture plan or audit decisions.
- Record real model identity, duration, event-loop metrics and SHA-256 of current material bytes; test crash/restart, 429, stale lease, replay and tamper detection.
- Close F4 only with passing observed E2E and attached evidence; then F5 controlled engineering tools and F6 operations.

## F4.3-G6 — Real intake-to-acceptance operator certification slice (local execution pending)
- Added `scripts/f43g6-intake-certification.sh` using actual Quarkus planning REST, durable planning audit and explicit operator acknowledgement before importing; delegates worker/quality to G5's production Event Bus path.
- Checks workflow correlation, approved planning audit record, final canonical SHA-256 and APPROVED audit linkage. Does not generate synthetic decisions.
- Fail closed on duplicate intake, transport ambiguity, rejected plan and unexpected states. Never automatically retries ambiguous mutations.
- Explicit approval acknowledgement is client-side only; operator token remains the server authorization. The first no-approval run creates intake then stops; continue through existing import and G5 endpoints after reviewing the persisted plan.
- Still pending: observed Quarkus/Ollama E2E evidence, material bytes, crash/restart, 429 saturation, stale lease, event-loop/worker metrics, actual model identity. F4 remains OPEN; do not start F5 based solely on this script.
### Validate
```bash
bash -n scripts/f43g6-intake-certification.sh
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F4.3-G7 — Fail-closed planning audit verification at execution import (local validation pending)
- Hardened `ExecutionHandoffResource.importAudited`: before importing a plan, reopen the immutable communication store and verify the exact planning artifact and APPROVED audit subject/workflow/task correlation. A planning status string and non-null audit ID are no longer sufficient.
- `ExecutionPlanAuditVerifier` is a pure policy boundary over durable records; no model call, automatic grant, project write, or event-loop JDBC. All file and database work stays within existing `@Blocking` REST.
- `ExecutionPlanAuditVerifierTest` covers BLOCKED audit, APPROVED audit with wrong subject, missing audit and durable reopen with correct APPROVED audit.
- Remaining F4 certification work: actual Quarkus/Ollama operator E2E results, material tamper, lease/crash recovery, 429, concurrency and event-loop metrics. Do not declare F4 closed.
### Validate
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionPlanAuditVerifierTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F4.3-G8 — Read-only durable recovery diagnosis (local validation pending)
- New opt-in `GET /api/v1/execution/workflows/{id}/recovery` with centralized workspace token, feature flags default off and `@Blocking` JDBC.
- Inspects durable task states, leases and model invocation reservations/effect phases. Never retries ambiguous calls, resets leases, approves audits, or modifies material. Identifies `AMBIGUOUS_INVOCATION`, `FILE_RECOVERY_REQUIRED` and expired leases for operator investigation.
- Tests cover fail-closed state classification and default-off HTTP access.
- Limitations: cross-connection point-in-time snapshot, no active Ollama runtime telemetry, no crash simulation or material-byte verification. F4 remains OPEN until measured end-to-end validation.
### Validation
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionRecoveryReportTest' --tests '*ExecutionRecoveryResourceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F4.3-G9 — Read-only restart state certification (operator run required)
- `scripts/f43g9-restart-certify.sh` captures canonical acceptance and durable recovery state before/after a manually controlled Quarkus restart, using existing authenticated production REST endpoints.
- Explicitly rejects wrong-token access; verifies workflow correlation and response contracts; compares exact task IDs, statuses, attempts, artifact/audit IDs and SHA-256, recovery diagnoses and attempt IDs. Optional `AIES_CERT_REQUIRE_MATERIAL=true` enforces material acceptance current-byte checks in both phases.
- Strict loopback-only, read-only, no automatic retry of model, no workflow mutation. `AIES_WORKSPACE_CHANGES_TOKEN` is canonical. Reports saved with umask 077 and no token or full model content.
- This does not cause a crash, prove model execution/latency, 429, concurrency or guarantee an atomic snapshot. Failures require manual reconciliation. F4 stays OPEN pending observed full E2E evidence.
### Validate
```bash
bash -n scripts/f43g9-restart-certify.sh
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F4.3-G10-A — Planning provenance preservation (first increment; F4 OPEN)

### Observed production failure (2026-10-13)
- Operator workflow `wf-cert-20261013034306` reached `REVISION_REQUIRED`, not approved. Planning artifact `0ef3defe-428f-4484-9f21-5fb43e42a84a` contained `sourceRefs: []` despite an intake source `src1`; audit `b0590c86-35c7-49fb-90e1-c891365246b3` complained about missing source traceability, task costs and acceptance criteria.
- The plan JSON already had `sourceId`, `specialistCost`, `specialistId`, Given/When/Then, and a budget. Do not treat every model finding as proven. Audit reported `structural-quality-auditor` but semantic auditing may have run after structural checks; auditor name alone does not establish model identity.
- G9 restart certification cannot run without an imported execution workflow; HTTP 404 and empty `execution_tasks` are expected before successful approved planning/import. No fabricated approval or DB mutation.

### Delivered G10-A scope
- `PlanningArtifactEvidence` derives immutable `sourceRefs` from validated intake sources actually used by plan requirements, preserving source order. Validates workflow correlation, requirement identity and task Given/When/Then/verification linkage; rejects mismatches before writing a planning artifact.
- `PlanningEventBus.prepareAudit` uses this evidence builder in place of `List.of()` for `sourceRefs`. No schema changes, external model calls, new REST endpoints or automatic retries.
- Focused unit tests: source preservation, mismatched workflow and changed task acceptance criteria.
- Existing records are immutable: previously rejected workflow must remain rejected. Create a **new** workflow ID/intake for a new certification; never overwrite the original audit.

### Next mandatory increments before F4 closure
1. **G10-B — Contract-aware deterministic planning audit.** Parse the exact persisted `planning-plan` representation, validate sourceRefs against authoritative intake, requirement→task coverage, DAG, per-task specialistCost and budget, and Given/When/Then. Emit machine-readable finding codes with precise fields. No lenient substring checks, no approving incomplete plans. Ensure sourceRefs are meaningful evidence references, not proof of external material existence.
2. **G10-C — Semantic audit evidence.** Persist whether semantic review ran, actual model identifier/version if observed, timing, structural and semantic finding origins, timeout/429 outcomes; reject false assertions about Ollama. Keep structural fail-closed and semantic opt-in.
3. **G10-D — Revision lifecycle.** Immutable new artifact per revision, explicit operator acknowledgement, bounded retries, replay/idempotency and lineage to original audit. Do not auto-retry a rejected immutable subject.
4. **G10-E — Operator certification harness.** Fix G5/G6 shell assumptions and test them: unset poll defaults under `set -u`, 404 non-JSON preflight, `curl` credential exposure in process args, response schema, two-phase resume, clear error codes. Verify centralized `AIES_WORKSPACE_CHANGES_TOKEN` only; no secret output.
5. **G10-F — Runtime negative evidence.** Run real Quarkus + Ollama planning → approved audit → import → real specialist → quality audit → canonical acceptance; verify material bytes if applicable. Exercise crash/restart (G9), expired lease, timeout/late reply, 429, concurrency, tamper, auth rejection, replay and event-loop latency with measured timestamps. Persist sanitized evidence in PR.
6. **F4 closure gate.** All applicable production paths and negative cases must have observed results, stable CI regression, verified audit/artifact hashes, zero false unlocks, and an explicit operator sign-off. Until then F4 remains **OPEN**; F5 must not be declared started based on these partial slices.

### G10-A validation (run in target checkout)
```bash
bash -n scripts/install.sh  # installer from archive, before installation
./gradlew :apps:control-plane:test --tests '*PlanningArtifactEvidenceTest' --no-daemon --console=plain
./gradlew :apps:control-plane:test --tests '*PlanningRestIntegrationTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Important limitations
- The source digest in intake is format-validated, **not** independently verified against external source bytes; no new claim of content integrity is made.
- Source references alone do not guarantee the semantic auditor will approve. An immutable revision and audit are still required.
- These tests are supplied but Gradle/Quarkus/Ollama execution is not claimed by the ZIP producer.

## F4.3-G10-B — Contract-aware structural planning audit (local validation pending)
### Implementation
- `PlanningArtifactQualityPolicy`: deterministic fail-closed parsing of the canonical JSON embedded in `planning-plan` markdown; no model, shell, external network, or project writes.
- Verifies workflow correlation, audit-ready state, one-to-one requirement/task mapping, assignment, acceptance criteria, dependency projection, per-task costs, total and budget, and a minimum count of unique provenance URI references.
- `CommunicationQualityPolicy` delegates only `planning-plan` to this policy; other artifact/message behavior remains unchanged. `CommunicationEventBus` continues its existing Event Bus path, durable audit and optional semantic stage. A structural pass does not imply semantic approval.
- Regression tests: valid planning contract; missing provenance; mutated task criteria; cost inconsistency; invalid JSON.
### Important limitations
- Existing planning artifacts lack an immutable source-ID-to-URI-to-digest map. URI count alone cannot prove exact source mapping or verify bytes/digest. **G10-C must add and verify this mapping without changing existing historical records**; never claim full provenance or digest verification from G10-B.
- Legacy rejected workflows remain immutable; create a new intake/workflow for observed approval.
- No Gradle/Quarkus/Ollama execution is claimed by the package author. No actual model identity or latency measured.
- `F4` remains **OPEN**; do not advance to F5 based solely on deterministic policy tests.
### Required local validation
```bash
./gradlew :apps:control-plane:test --tests '*PlanningArtifactQualityPolicyTest' --tests '*PlanningRestIntegrationTest' --tests '*PlanningArtifactEvidenceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```
### G10-C next — Complete provenance and audit integrity
1. Persist canonical source ID, URI and SHA-256 digest mapping inside immutable planning evidence, with exact requirement/task joins; verify digest against bytes when source material is available. Missing bytes must not be misreported as verified.
2. Separate structural findings (machine-checkable) from semantic model findings; preserve the model identity and exact decision, no invented audit approval.
3. Add negative tests: mismatched source ID/URI, changed digest, duplicate sources, invalid DAG, budget, missing criteria, corrupted artifact SHA, stale audit replay.
4. Re-run real intake→audit with a new workflow, retain both APPROVED and REVISION_REQUIRED immutable histories.
5. Continue G9 restart, execution/material SHA, 429, timeout/late reply, lease fencing and observability certification before F4 closure.
### Lessons
- A source URI list is not equivalent to a cryptographically verifiable provenance graph.
- Generic markdown-quality heuristics cannot replace contract-specific checks; fail closed on missing/corrupted canonical data.
- Test-local structural APPROVED is not evidence of real semantic Ollama APPROVED.

## F4.3-G10-C — Exact immutable planning source manifest (local validation pending)
- Adds `PlanningProvenance` to embed canonical JSON source mapping (`id`, `uri`, lowercase SHA-256 `digest`) into the same immutable `planning-plan` artifact before append. Mapping is derived from the validated intake and exact requirements; no synthetic source is introduced.
- Structural audit fails closed on missing/duplicate manifest, malformed JSON, invalid SHA format, mismatched source IDs, duplicate URIs and disagreement with artifact `sourceRefs`.
- Production `PlanningEventBus.prepareAudit` generates manifest before durable append; the G10-B unit fixture is updated to use the same producer.
- **Limitations:** digest is an *intake claim*, not a byte-level verification of external material; manifest is not independently signed, and an attacker able to rewrite all artifact fields and stored SHA could forge a self-consistent record. Historical records are immutable and will not be backfilled. No actual Ollama/model timing or E2E certified.
- **G10-D next:** revision lifecycle with immutable parent lineage, explicit operator acknowledgement, no mutation of rejected audit, bounded attempts and idempotent replay. Before F4 closure also require G10-E operator script repairs and G10-F observed Quarkus/Ollama E2E plus negative recovery/429/lease/material tests.
- **F4 remains OPEN.** Do not advance to F5.
### Validate
```bash
./gradlew :apps:control-plane:test --tests '*PlanningArtifactQualityPolicyTest' --tests '*PlanningRestIntegrationTest' --tests '*PlanningArtifactEvidenceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### G10-C hotfix — Markdown provenance newline serialization
- Corrected escaped `\\n` literals in `PlanningProvenance.embed`: actual LF delimiters are required for the canonical markdown section parser. The earlier implementation emitted literal backslash-n sequences, causing structural audit to reject valid plans and the Quarkus planning integration to fail closed.
- The structural policy remains strict; no bypass or approval fabrication. Historical immutable rejected records remain unchanged. New workflow IDs are required for live recertification.
- Run the targeted planning tests and full clean suite before merging. This package was syntax-checked, not Gradle-executed by its author.

## F4.3-G10-D — Immutable planning revision lineage (implementation; local validation pending)
- Adds `PlanningRevisionStore` and authenticated `PlanningRevisionResource`, opt-in via `AIES_PLANNING_REVISIONS_ENABLED=true` (defaults false).
- Operator `POST /api/v1/planning/workflows/{parentId}/revisions` with JSON `{requestId,expectedParentAuditId,intake}` creates a **new workflow ID**, new intake and durable outbox event in the SAME SQLite transaction as append-only `planning_revisions` lineage. Parent must remain `REVISION_REQUIRED` and audit ID must match exactly; parent plan, artifact and audit are NEVER overwritten.
- `GET /api/v1/planning/revisions/{requestId}` enables read-only reconciliation after ambiguous HTTP timeouts. Same idempotency request replays with 200; different payload under same key conflicts with 409. New child returns 201, starts `RECEIVED`, must pass normal planning/semantic audit before import. Depth bounded to 3, unchanged intake rejected, collisions rollback.
- No automatic revision from LLM, no synthetic APPROVED audit, no direct specialist invocation or execution. Internal workflow uses existing durable outbox and Vert.x Event Bus.
- JUnit coverage for durable replay after restart, parent immutability, stale audit, empty revision, ID collisions and rollback. **Local Gradle/Quarkus/Ollama runs not performed by artifact producer.**
- Caveats: `planning_revisions` is append-only by application convention, not SQL triggers; DBA-level tampering remains possible. Request hash is canonical Jackson serialization of validated intake (not a signature). Parent audit ID is compared to the persisted snapshot, but this endpoint does not independently recalculate immutable audit record bytes. GET `status=RECEIVED` describes creation state, not live child state; inspect child workflow via existing planning GET.
- Operational warning: use `AIES_WORKSPACE_CHANGES_TOKEN` as the centralized operator credential. Do not print or commit token. This feature does not repair earlier G5/G6 operator scripts.
- **F4 remains OPEN.** G10-E next: production operator certification script hardening (two-phase resume, 404 non-JSON, schema validation, no token argv leakage, restart snapshots, fixture tests). G10-F real Quarkus/Ollama evidence; G10-G negative security/lease/saturation/material tests. No F5 until F4 closure.
### Local verification
```bash
./gradlew :apps:control-plane:test --tests '*PlanningRevisionStoreTest' --tests '*PlanningRestIntegrationTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F4.3-G10-E — Fail-closed operator CLI hardening (local validation pending)
- Replaces G5 and G6 scripts with strict Bash controls and a shared `f43g10e-http.sh` transport. Uses only centralized `AIES_WORKSPACE_CHANGES_TOKEN`, not the removed `AIES_WORKSPACE_TOKEN`.
- Credentials supplied through curl `--config -` via stdin, JSON escaped, not argv. Only explicit loopback URLs; 200/201 require JSON object; 404 and other errors are handled without assuming JSON. No automatic retry after ambiguous mutation.
- G6 supports `create`, `inspect`, `resume`. `inspect` does not mutate and verifies exact canonical intake against durable planning snapshot. `resume` requires the same intake, an actual `READY_FOR_ASSIGNMENT` plan with artifact and audit IDs, explicit `AIES_OPERATOR_APPROVE_WORKFLOW`, and absent execution acceptance before a **single** import attempt. It does not automatically execute the worker or claim a human reviewed the plan.
- G5 checks canonical acceptance before execution, rejects ambiguous/pending/dead/running tasks, and runs one bounded worker attempt and actual quality review at a time. Non-approved audit never yields a success assertion. Existing `/tasks` contract is a top-level JSON array, not `{tasks:[]}`.
- Shell regression fixtures exercise non-JSON 404, strict mode and token non-disclosure in curl argv/logs. `scripts/f43g10e-shell-tests.sh` is local, not a Quarkus/Ollama test. G9 remains read-only but still passes token in curl argv and **requires follow-up security remediation**; do not claim G9 hardened by this increment.
- Known limits: G6 `create` cannot distinguish unauthorized 404 from absent workflow, but never retries the POST. G6 `resume` is an operator acknowledgment, not cryptographic proof of review. G5 worker actions can time out after durable side effects: operator must reconcile manually. G9 restart proof, model identity, timing, 429, lease, material tamper and real end-to-end certification remain pending.
- **F4 OPEN**. Next G10-F: harden G9 token handling and implement observed Quarkus/Ollama operator certification, restart and negative cases. Do not start F5 until observed evidence and sign-off.
### Validation
```bash
bash scripts/f43g10e-shell-tests.sh
bash -n scripts/f43g5-operator-e2e.sh scripts/f43g6-intake-certification.sh scripts/f43g9-restart-certify.sh
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## F4.3-G10-F — Safe restart transport and production evidence collection (operator certification pending)

### Delivered
- G9 restart snapshot now uses the centralized G10-E HTTP helper for every call, including the negative-token check. No operator secret in curl argv; non-JSON 404 is valid only for negative authorization. Loopback-only, read-only, fail-closed.
- `scripts/f43g10f-certification-report.sh` reads real production acceptance/recovery and optionally material-acceptance. Emits a private, sanitized, non-overwriting JSON observation and its SHA-256. No model output, secrets, or artificial decisions. It does not call workers or approve audits.
- Existing G9 before/after snapshots still require an actual manual Quarkus restart against the same SQLite data root. A matching snapshot alone is not proof a restart occurred.

### Runbook
```bash
# Start real Quarkus/Ollama separately with opt-in flags; source .env securely.
export AIES_WORKFLOW_ID='an-existing-approved-imported-workflow'
export AIES_CERT_DIR="$PWD/.local-certifications/g10f-01"
# Set AIES_WORKSPACE_CHANGES_TOKEN in the shell, never in committed files.
bash scripts/f43g10f-certification-report.sh
bash scripts/f43g9-restart-certify.sh before
# Manually stop and restart Quarkus, preserving data root; do not run worker meanwhile.
bash scripts/f43g9-restart-certify.sh after
```

### Closure criteria still OPEN
1. New immutable intake with exact source manifest and approved planning audit; explicit operator review and import; real specialist Ollama invocation and real quality audit; canonical VERIFIED. Record actual model identity, timing, request outcomes and persisted IDs without fabricating fields.
2. Negative cases: invalid token, rejected audit, stale lease and late response, replay, concurrency/429, tampered artifact and current material bytes. No DAG unlock on failure. Verify durable restart with actual process stop/start and unchanged data root.
3. Repeatable automated tests in CI and operator evidence with UTC timestamps, immutable hashes, redacted logs and human sign-off. Do not confuse a sanitized observation with comprehensive E2E proof.
4. G10-G next: add deterministic negative runtime/transport fixtures, opt-in Quarkus/Ollama certification harness and measured model/runtime observability. F4 remains **OPEN**; F5 is not started.

### Validation
```bash
bash -n scripts/f43g9-restart-certify.sh scripts/f43g10f-certification-report.sh
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Caveats
- Report is a read-only observation; a VERIFIED canonical manifest does not prove semantic correctness or real-world task completion.
- G9 and report are not atomic cross-endpoint snapshots; concurrent mutations can cause drift. Run only in an operator-controlled quiet window.
- No Gradle, Quarkus, Ollama, 429 or crash/restart tests were executed by the package producer.

## F4.3-G10-G — Deterministic negative operator transport fixtures and evidence gate (F4 OPEN)

### Scope delivered
- `scripts/f43g10g-negative-tests.sh` exercises the actual shared G10-E Bash transport against an isolated mock curl executable. Cases: HTML 404, JSON 200, 409 conflict, 429 saturation response, malformed successful JSON, ambiguous POST transport failure, invalid workflow IDs and no token disclosure through curl argv or test logs. It never invokes Quarkus, Ollama, or a production endpoint.
- `scripts/f43g10g-evidence-check.sh` validates the schema, workflow correlation and task count of an **existing** G10-F read-only evidence JSON. It refuses incomplete canonical acceptance, optionally requires `MATERIAL_VERIFIED`, and checks optional before/after restart snapshots for drift. It does not create, approve or alter any workflow or audit, and does not infer that a restart actually happened.
- `scripts/f43g10g-shell-tests.sh` runs the negative fixtures and evidence gate regressions. All scripts are Bash-only, fail closed, with no Python runtime dependency.

### Required validation
```bash
bash scripts/f43g10e-shell-tests.sh
bash scripts/f43g10g-shell-tests.sh
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Live certification procedure (operator only)
1. Create a **new** immutable intake and run the existing G6 `create`/`inspect`/`resume` flow. Do not revise historical rejected audits in place. Observe an actual APPROVED planning audit and perform explicit review before import.
2. Execute G5 against real opt-in Quarkus/Ollama, capture actual execution audit and canonical acceptance. If workspace material applies, set `AIES_CERT_REQUIRE_MATERIAL=true` and use G10-F report to record material acceptance.
3. Set `AIES_WORKFLOW_ID` and `AIES_CERT_DIR`, run G10-F report, G9 `before`, manually restart Quarkus with the same durable data root, G9 `after`, then `bash scripts/f43g10g-evidence-check.sh`. Use a fresh private evidence directory; never overwrite immutable observations.
4. Separately record actual lease expiration, late reply, replay, 429/concurrency, tamper, auth rejection and event-loop latency tests. A mocked 429 is **not** production 429 evidence. Record timestamps, model identity only when observed, artifact/audit IDs, redacted logs and operator sign-off.

### Remaining gaps and next hito: F4.3-G10-H
- Implement controlled, opt-in production negative scenario runner with durable-state reconciliation for timeout, late reply, lease fencing and saturation; prohibit automatic replay of ambiguous mutations. Add Quarkus/JUnit coverage for exact negative contracts.
- Capture real model identity and timing at the Ollama adapter boundary, and observed event-loop/worker metrics; avoid fabricated timing or model claims.
- Add material byte-tamper and audit replay tests with independent SHA-256 verification, isolated test roots and no production database writes.
- Complete actual restart certification and signed operator checklist. **F4 remains OPEN; F5 not started.**

### Honesty of validation
The archive author can validate shell syntax and mock fixtures locally, but has not run this package against the user's Quarkus/Ollama instance or certified a live F4 workflow. Existing G10-F report and G9 snapshots are read-only observations, not comprehensive end-to-end proof.

## F4.3-G10-H — SQLite lease fencing, concurrent claim and restart negatives (local validation pending)

### Delivered in this increment
- `F43G10HExecutionSafetyTest` exercises the actual `ExecutionHandoffStore` against isolated SQLite files, with a two-task dependency DAG. No mocked database, LLM approvals, external service or production data root.
- Stale/wrong lease tokens cannot stage evidence or fail another attempt. After a bounded retry, a new token is issued and the previous token cannot alter the state. Staged evidence does not unlock a dependent task without a verified audit.
- Eight independent connections race to claim the same workflow; only one active claim may exist. A reopened store checks durable lease and attempt count.
- Terminal retry exhaustion persists across SQLite reopening; the dependent task stays PENDING and canonical acceptance remains INCOMPLETE.
- Fail-closed installer refuses overwriting an existing test and appends only to this canonical roadmap.

### Validation required on user's machine
```bash
./gradlew :apps:control-plane:test --tests '*F43G10HExecutionSafetyTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Explicit limits and next milestone G10-I
- These tests do not force real wall-clock lease expiration, kill a JVM mid-transaction, test multiprocess SQLite locking, prove 429 under real Ollama load, verify workspace bytes or measure event-loop latency. An active lease replaced after a controlled failure is not proof of expiration behavior.
- G10-I must cover deterministic clock-controlled expiry or bounded real expiry, late model reply, genuine subprocess crash/restart, material tamper, audit replay, live Ollama identity/timing and controlled saturation. Preserve immutable evidence and operator review; no automatic retry of ambiguous mutations.
- F4 remains **OPEN** until observed, reproducible live acceptance and negative certification with operator sign-off. Do not start F5 solely because unit tests pass.

### Lessons
- A successful HTTP fixture cannot prove SQLite fencing. Exercise the store through multiple independently opened connections and assert durable state after reopening.
- Keep negative tests isolated from real operator data and distinguish simulated restart (connection reopen) from an actual process restart.

## F4.3-G10-I — Deterministic durable negative recovery and material integrity (F4 OPEN)

### Delivered
- `F43G10IFailureInjectionTest` exercises the real execution SQLite store and communication journal in isolated `@TempDir` roots.
- A test-only SQL clock injection forces an expired lease without waiting for wall-clock time. A late stale attempt cannot stage evidence, fail the successor, or unlock the DAG. A new attempt obtains a distinct token and durable attempts=2 after reopening.
- When attempts are exhausted, an expired lease is not reclaimed. The task may remain LEASED with an expired token pending explicit recovery; this is **not** proof of automatic dead-lettering. Acceptance stays INCOMPLETE.
- A rejected audit cannot complete a task; approved audit replay is idempotent, and conflicting replay is rejected. Only actual approved canonical evidence unlocks a dependent task.
- Altering material bytes after artifact creation but before approval is rejected by SHA-256 verification. The task remains EVIDENCE_PENDING and dependencies stay blocked.

### Required local verification
```bash
./gradlew :apps:control-plane:test --tests '*F43G10IFailureInjectionTest' --no-daemon --console=plain
./gradlew :apps:control-plane:test --tests '*F43G10HExecutionSafetyTest' --tests '*F43G3TimeoutRecoveryTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Not yet certified — G10-J
- These are deterministic component tests, **not** real JVM crash/restart, multi-process lock, production HTTP 429, real Ollama identity/timing or event-loop latency measurements.
- G10-J should provide opt-in, isolated real-process Quarkus/Ollama certification with controlled crash boundaries, before/after restart snapshots, saturated real specialist capacity, operator-signoff and redacted immutable evidence. No automatic replay of ambiguous model invocations or approvals.
- Keep F4 OPEN until the actual operator E2E and all negative requirements have been observed and signed off. No F5 yet.

### Learning
- Distinguish expired-lease fencing from automatic terminal-state recovery. Do not assert that an expired lease transitions to DEAD unless production logic does so.
- Use independent persisted SQLite and actual file bytes for negative evidence; no simulated approved audit or modified production database.

## F4.3-G10-J — Linux process restart evidence with read-only runtime certification (F4 OPEN)

### Delivered
- `f43g10j-runtime-restart.sh` is an **opt-in Linux operator procedure**, not an automatic restart or crash injector. It invokes G9's authenticated read-only acceptance/recovery snapshot and captures a distinct Linux PID/starttime observation before and after an operator-controlled Quarkus restart. Uses existing G10-E curl secret-safe transport and only loopback HTTP.
- The operator explicitly supplies `AIES_QUARKUS_PID` and `AIES_DATA_ROOT` in both phases. The script refuses an unchanged process identity or changed canonical root; it hashes, but never records, process argv. It does not independently prove that Quarkus is configured to use the supplied root.
- `f43g10j-evidence-verify.sh` checks both observations, snapshot equality and the SHA-256 of each snapshot; it is read-only and can be rerun offline.
- `f43g10j-shell-tests.sh` covers a passing fixture, unchanged PID rejection and tampered snapshot rejection. No production writes, no fabricated audit decisions or Ollama results.

### Operator runbook (Linux; Quarkus running independently)
```bash
export AIES_WORKFLOW_ID='existing-imported-workflow'
export AIES_CERT_DIR="$PWD/.local-certifications/g10j-01"
export AIES_DATA_ROOT='/absolute/path/to/actual/quarkus-data-root'
# Export AIES_WORKSPACE_CHANGES_TOKEN securely, do not echo or commit it.
export AIES_QUARKUS_PID='ACTUAL_PID_BEFORE'
bash scripts/f43g10j-runtime-restart.sh before
# Manually stop Quarkus; verify it stopped. Restart it with SAME data root.
export AIES_QUARKUS_PID='ACTUAL_NEW_PID_AFTER'
bash scripts/f43g10j-runtime-restart.sh after
bash scripts/f43g10j-evidence-verify.sh
bash scripts/f43g10g-evidence-check.sh # only if G10-F report also exists
```

### Local validation
```bash
bash scripts/f43g10j-shell-tests.sh
bash -n scripts/f43g10j-*.sh
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Explicit limitations and G10-K
- G10-J verifies *operator-supplied* process identity metadata and stable HTTP state. It is not a controlled JVM kill, cannot attest actual Quarkus configuration, and does not prove SQLite bytes are unchanged, a real Ollama model was called, event-loop latency, 429 saturation, or full end-to-end F4 acceptance.
- G10-K must implement opt-in isolated subprocess orchestration with actual PID supervision and fail-closed crash points; instrument actual Ollama identity and timing at the adapter, demonstrate real 429/backpressure, and produce an operator-reviewable immutable certification bundle. No automatic replay of ambiguous POST/model invocations. **F4 remains OPEN.**
