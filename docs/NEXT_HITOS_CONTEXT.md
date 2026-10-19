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

## S0 — Specialists 2.0: especificación contractual (propuesta; F4 OPEN)

### Decisiones confirmadas
- Composición de perfiles y competencias; un especialista es un contrato de dominio, no un modelo ni una licencia profesional.
- Catálogo inicial más propuestas de IA sujetas a aprobación humana; nunca autoactivación.
- Coordinador dinámico que propone; motor determinista valida dependencias, presupuesto, elegibilidad y autorizaciones.
- Autonomía por riesgo de la acción, con revisión y escalamiento según impacto. Roles no confieren permisos.

### Artefactos S0 incorporados
- `docs/architecture/ADR-S0-SPECIALISTS-2.0.md`: ADR-001 a ADR-005, amenazas, decisiones abiertas, migración y aceptación.
- `docs/architecture/S0-S1-COMPATIBILITY-AND-PLAN.md`: matriz de compatibilidad v1, fases S1 y gates.
- `docs/contracts/specialist-profile-v2.schema.json` y `docs/contracts/specialist-selection-v2.schema.json`: **esquemas propuestos**, no contratos runtime habilitados.
- `docs/fixtures/specialists/`: cuatro especialistas legados, perfil válido y negativo de permiso implícito.
- `scripts/s0-specialists-contract-tests.sh`: smoke determinista documental/fixtures (requiere jq); no sustituye validador JSON Schema ni suite Java.

### Camino ajustado y dependencias
1. **F4.3-G10-K sigue pendiente**: certificación real aislada Quarkus/Ollama y negativos; F4 permanece abierto. S0 no reemplaza ni cierra esta obligación.
2. **S0 revisión de arquitectura**: aprobar ADR, canonicalización interoperable y vectores hash, scopes de operador, revocación en vuelo, revisión/cache y retención; confirmar esquemas y pruebas. S0 no altera runtime.
3. **S1 registro gobernado**: SQLite durable, perfiles/capacidades versionados, aprobación/autorización, CAS, outbox, flag apagado por defecto, adaptador Event Bus v1 compatible, v2 opt-in y rollback por flag.
4. **S2**: perfiles de modelos y evaluación de competencias, no incluidos en S1.
5. **S3–S5**: metodologías ejecutables, equipos multidisciplinarios, auditoría por riesgo y evaluación continua. No habilitar capacidades legales/financieras de alto impacto sin fuentes, políticas y revisión profesional apropiadas.

### Criterios de aceptación
- S0-AC01..07 y S1-AC01..14 definidos en ADR. Los artefactos S0 se consideran **propuestos**, no aprobados ni certificados por el mero hecho de instalarse.
- Validación local solicitada: `bash scripts/s0-specialists-contract-tests.sh`, `git diff --check`, `./gradlew clean test --no-daemon --console=plain`.
- No se ha ejecutado Quarkus/Ollama ni la certificación G10-K por esta entrega. No afirmar que S1 está implementado.

### Aprendizajes
- `MethodologyRouter` puede reconocer documentación aunque el catálogo no ofrezca `documentation`: `BLOCKED_SPECIALIST` es un rechazo correcto; no inventar especialista ni alterar SQLite.
- `PlanningPolicy` rechaza requisitos críticos con verificación `INSPECTION`; `NEEDS_CLARIFICATION` precede a selección.
- G6 debe reconocer `NEEDS_CLARIFICATION`, `BLOCKED_SPECIALIST` y `BLOCKED_BUDGET` como estados de negocio no aprobatorios sin permitir importación; corrección operativa separada de S0.

## S1-A — Specialists 2.0: registro SQLite aislado (entrega para validación; F4 OPEN)

### Alcance implementado
- `SpecialistRegistryManifest`: validación estricta de metadatos v2; campos desconocidos rechazados, capacidades compuestas, roles, límites y versiones. Canonicalización JSON recursiva por claves de objeto ordenadas (arrays preservan orden), UTF-8 y SHA-256. Esta canonicalización es **propia de S1-A**, no equivale a RFC 8785/JCS; no aceptar hashes de otra implementación sin vectores de prueba.
- `SpecialistRegistryStore`: SQLite independiente, tablas aditivas, WAL, `busy_timeout`, unicidad por `(id,version)`, lectura verificando SHA-256 y JSON canónico, propuestas solo `DRAFT`, semillas legadas confiables con transacción e idempotencia y detección de colisiones.
- Sin nuevas APIs REST ni Event Bus, sin cambios a `SpecialistEventBus` ni `SpecialistCatalog`, sin cambios a selección, planificación o ejecución. Los cuatro especialistas legados se mantienen como fuente activa del runtime existente. `bootstrapLegacy()` es un paso explícito para un registro aislado, no se ejecuta automáticamente en arranque.
- Tests JUnit para persistencia/reapertura, hash determinista, rechazo de campos y permisos implícitos, semilla equivalente, colisiones y tamper. El paquete incluye instalador Bash fail-closed basado en blob exacto de `main`.

### Seguridad y límites
- No se implementan transiciones `DRAFT → ACTIVE`, aprobación, CAS de publicación, outbox, permisos, registro de modelos ni selección v2. S1-B deberá implementar autorización de operador por scope, revisión independiente, máquina de estados, CAS, outbox y revocación. S1-C integrará selector v1/v2 detrás de flag apagado por defecto y pruebas de compatibilidad.
- Un perfil es metadato no confiable; `requiredTools` no concede permisos. No se ejecutan herramientas ni modelos, no se altera la auditoría y no se autoaprueba un perfil propuesto.
- `bootstrapLegacy()` es una operación confiable de migración manual y debe invocarse solo bajo procedimiento de operador, con copia de seguridad y reconciliación. S1-A no expone endpoint para ejecutarla.
- Validación JSON actual es subconjunto estricto del esquema S0 con semántica adicional; compatibilidad exhaustiva y vectores canónicos externos pendientes para S1-B.

### Validación obligatoria
```bash
./gradlew :apps:control-plane:test --tests '*SpecialistRegistryStoreTest' --no-daemon --console=plain
./gradlew :apps:control-plane:test --tests '*SpecialistCatalogTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Siguiente hito: S1-B
- Autorización real de operador con scopes separados; propuesta/validación/aprobación/publicación/revocación con CAS, auditoría durable, outbox y verificación de separación de funciones.
- Reconciliación de revocación con intentos en vuelo, idempotencia, concurrencia multiproceso, pruebas negativas y compatibilidad sin alterar v1.
- Posteriormente S1-C: selección por registro v2 y puente v1 opt-in, sin fallback silencioso ante corrupción o indisponibilidad.
- **F4/G10-K continúa pendiente; no afirmar certificación de Quarkus/Ollama ni cierre de F4.**

## S1-C.1 — Verificación de continuidad de gobernanza (validación local pendiente)
- `SpecialistGovernanceIntegrity` verifica la consistencia de estados/revisiones y el historial de decisiones de S1-B; detecta decisiones faltantes, huérfanas, transiciones incompatibles y divergencias de estado.
- Tests SQLite con alteraciones intencionales. Verificación aislada, no integrada en selección ni REST; no equivale a auditoría criptográficamente inmutable.
- No habilitar v2 ni crear permisos por un token compartido: `WorkspaceOperatorAuthorizer` autentica un secreto, no identidades individuales con scopes.
- Siguiente incremento: S1-C.2, snapshot consistente con escritores, integridad de auditoría anclada, autenticación real de operador, separación de funciones y pruebas multiproceso; luego selector v2 opt-in con rollback.
- F4/G10-K sigue OPEN. No se ejecutó Gradle ni Ollama durante la generación del paquete; validar localmente.

## S1-C.2 — Gateway aislado de operadores (validación local pendiente)
- Nuevo `SpecialistOperatorGateway`: catálogo de identidades configuradas fuera de banda, secretos aleatorios con digest SHA-256, scopes exactos por acción, comparación de credenciales sin registrar secretos.
- Trigger SQLite que rechaza en la misma transacción una activación por el aprobador de la revisión anterior; S1-B mantiene CAS y auditoría.
- S1-C.1 se comprueba antes de cada mutación; pruebas JUnit de credencial errónea, scope insuficiente, separación de aprobación/activación, persistencia y configuración ambigua.
- No hay REST, Event Bus v2 ni selección habilitada. La autenticación de usuarios HTTP, la separación del proponente, snapshot único concurrente, auditoría anclada y pruebas multiproceso siguen pendientes. No confundir SHA-256 de secretos aleatorios con almacenamiento seguro de contraseñas humanas.
- Validar: `./gradlew :apps:control-plane:test --tests '*SpecialistOperatorGatewayTest' --no-daemon --console=plain`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`.
- F4/G10-K sigue abierto.


## S1-C.3-B — Migración histórica controlada del outbox (entrega pendiente de validación local)

### Alcance implementado
- `SpecialistGovernanceHistoryMigration`: migración explícita y transaccional de decisiones anteriores al outbox; verifica primero la continuidad S1-C.1 y rechaza corrupción.
- Registra las decisiones preexistentes como **baseline histórico**, separado de eventos nuevos: `specialist_governance_outbox_baseline`. Esas decisiones NO deben ser publicadas ni reejecutadas.
- Crea la tabla y el trigger del outbox en una misma transacción SQLite `BEGIN IMMEDIATE`, con rollback ante fallos.
- `dispatchableCount` cuenta exclusivamente eventos no marcados como baseline; es una consulta de diagnóstico, no un consumidor.
- Reejecución de migración no amplía el baseline ni reenvía eventos. Si detecta outbox parcial sin baseline, rechaza para reconciliación manual.
- Pruebas JUnit para historia previa, separación entre eventos históricos y nuevos, reejecución y corrupción.

### Seguridad y límites
- Migración **offline**, invocada únicamente por operador autorizado tras backup; no ejecutarla desde el LLM, endpoint o arranque automático.
- La migración no implementa todavía idempotencia durable de comandos, concurrencia multiproceso, consumidor, acknowledgements, anclaje criptográfico ni crash-injection.
- Un outbox completo existente sin baseline no se reclasifica como histórico; requiere decisión explícita del operador.
- Mantener Event Bus v1 y selector v2 intactos y deshabilitados. F4/G10-K permanece OPEN.

### Validación y siguiente incremento
- Ejecutar `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceHistoryMigrationTest' --no-daemon --console=plain`.
- Ejecutar `./gradlew clean test --no-daemon --console=plain` y `git diff --check`.
- Registrar resultados reales antes de PR; no afirmar pruebas ejecutadas durante generación del ZIP.
- Siguiente S1-C.3-C: idempotencia durable de comandos con fingerprint actor/acción/revisión/hash, concurrencia multiproceso, snapshot consistente y recuperación con inyección de fallos.

## S1-C.3-C1 — Snapshot consistente de integridad SQLite (pendiente de validación local)

- Se añade `SpecialistGovernanceSnapshot` como verificador **read-only** de correspondencia entre decisiones, outbox y baseline histórico.
- Una única conexión y transacción de lectura proporcionan un snapshot SQLite consistente; las verificaciones anteriores de `SpecialistGovernanceOutbox.verify()` usaban dos conexiones y podían observar commits diferentes.
- Detección de eventos faltantes, huérfanos, campos divergentes y baseline huérfano; conteo de eventos nuevos excluyendo el baseline. La ausencia de esquema falla cerrado.
- Pruebas JUnit sobre SQLite para faltantes, baseline y registros huérfanos.
- **No se sustituye todavía el verificador existente ni se integra automáticamente con el gateway.** La verificación de cadena de estados S1-C.1 sigue siendo una obligación independiente.
- Pendiente S1-C.3-C2: idempotencia durable dentro de la transacción de `SpecialistGovernanceService`, prueba de concurrencia multiproceso, crash injection y recuperación. No se debe presentar S1-C.3-C como cerrado.
- Sin endpoints, ejecución automática, publicación del outbox ni selector v2. Event Bus v1 intacto. F4/G10-K continúa OPEN.
- Validación local pendiente: `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceSnapshotTest' --no-daemon --console=plain`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.

## S1-C.3-C2 — Idempotencia durable de transiciones de gobernanza (pendiente de validación local)

### Alcance implementado
- Sobrecarga opt-in de `SpecialistGovernanceService.transition(..., commandKey)`; mantiene la firma existente y el comportamiento de llamadas sin clave.
- `specialist_governance_idempotency`: clave única durable, parámetros de comando y resultado persistido.
- Replays con identidad, acción, revisión, versión y hash idénticos devuelven la decisión confirmada, sin repetir mutación ni trigger de outbox.
- Reutilización de clave con contenido distinto falla cerrado. Autorización de scopes y verificación de manifiesto preceden al replay.
- Registro de idempotencia se escribe en la misma transacción SQLite que estado y decisión; trigger del outbox sigue participando en esa transacción si está instalado.
- Sobrecarga opt-in en `SpecialistOperatorGateway`: credencial y scope se verifican antes de invocar replay.
- Pruebas JUnit: reapertura, replay, conflicto de clave y rollback tras revisión obsoleta.

### Límites, evidencia y continuidad
- No se activa automáticamente el outbox, ni se añade un endpoint. Event Bus v1 y selector v2 intactos.
- No se certifican aún concurrencia multiproceso, fallo abrupto del proceso, entrega outbox ni auditoría anclada.
- La clave debe provenir de un operador autenticado; no aceptar claves generadas por un modelo no confiable.
- Validación local pendiente: suite específica y `./gradlew clean test --no-daemon --console=plain`; documentar resultados antes de PR.
- Siguiente S1-C.3-C3: pruebas JVM multiproceso, crash-injection, recuperación y endurecimiento de integridad.
- F4/G10-K permanece OPEN.

## S1-C.3-C3-A — Certificación inicial de concurrencia (entrega pendiente de validación)

### Objetivo y alcance real
- Prueba JUnit con dos instancias independientes de `SpecialistGovernanceService` y dos conexiones SQLite compitiendo por la misma revisión CAS. Verifica una única decisión y un único registro de idempotencia confirmados.
- Prueba de **dos procesos JVM reales** con `ProcessBuilder`, conectados a una misma base SQLite, compitiendo por insertar la misma clave durable bajo `BEGIN IMMEDIATE`. Verifica exclusión y persistencia entre procesos.
- Worker de pruebas aislado sin REST, modelos ni credenciales. Su transacción SQL verifica una propiedad de SQLite, **no** reemplaza una prueba end-to-end de dos gateways.
- No se modifica código productivo; el incremento es deliberadamente de certificación y detección de fallos, no de funcionalidad nueva.

### Criterios y limitaciones
- La suite debe ejecutar ambas pruebas y terminar satisfactoriamente antes del PR. Si revela carrera, bloqueo o `SQLITE_BUSY`, corregir la implementación productiva antes de declarar cierre.
- Pendientes: pruebas multiproceso end-to-end del servicio completo, inyección de caída de proceso antes/después del commit, recuperación WAL y reconciliación de idempotencia con decisiones y outbox.
- No hay garantía exactly-once de entrega ni auditoría criptográficamente inmutable.
- Event Bus v1 intacto, selector v2 deshabilitado, F4/G10-K continúa OPEN.

### Comandos de verificación
- `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceMultiprocessTest' --no-daemon --console=plain`
- `./gradlew clean test --no-daemon --console=plain`
- `git diff --check`
- Documentar resultados reales y advertencias antes de cerrar PR.

### Siguiente incremento
- S1-C.3-C3-B: corrección de carreras detectadas, prueba end-to-end entre JVM y crash-injection antes/después de commit; verificación de recuperación y reconciliación durable.

## S1-C.3-C3-B1 — Transiciones reales entre JVM y reinicio (validación local pendiente)

### Implementación
- `SpecialistGovernanceProcessWorker`: proceso JVM independiente que ejecuta `SpecialistGovernanceService.transition(...)` con idempotencia real, sin SQL simulado.
- `SpecialistGovernanceProcessRecoveryTest`: dos JVM concurrentes con claves diferentes y misma revisión; solo una debe confirmar la transición. Comprueba decisiones e idempotencia persistidas.
- Prueba de respuesta perdida: JVM termina con `Runtime.halt(32)` **después de retornar `transition()`**, que ya realizó commit; una segunda JVM reintenta la misma clave y debe recuperar el resultado sin duplicados.
- Prueba de terminación **antes de invocar** la transición: la clave no queda reservada y otra JVM puede ejecutar el comando.
- Sin modificación de código productivo, endpoints, Event Bus v1 ni flags del selector v2.

### Límites de certificación
- **No se prueba todavía un crash dentro de la transacción abierta**, ni un corte de energía, ni el consumidor del outbox. La prueba `halt-before` ocurre antes de comenzar la transición y no debe presentarse como crash-injection intratransaccional.
- La prueba `halt-after` certifica reintento tras commit con respuesta no entregada, no pérdida de datos ante corte de energía.
- No hay exactamente-una-vez en transporte ni auditoría anclada. La integridad de outbox debe certificarse con migración offline explícita y snapshot cuando corresponda.
- Si se detectan bloqueos, errores de clase, o carreras, corregir antes de PR. No declarar S1-C.3-C3 completamente cerrado.

### Validación obligatoria
- `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceProcessRecoveryTest' --no-daemon --console=plain`
- `./gradlew clean test --no-daemon --console=plain`
- `git diff --check`
- Registrar resultados reales antes de cierre.

### Siguiente incremento: S1-C.3-C3-B2
- Inyección de caída *dentro* de una transacción SQLite y comprobación de rollback/WAL, reconciliación de outbox y baseline, y pruebas de concurrencia sostenida y recuperación tras fallo.
- F4/G10-K permanece OPEN; selector v2 sigue deshabilitado.

## H01 / S1-C.3-C3-B2 — Crash intratransaccional real (implementación preparada; validación Fedora pendiente)

### Alcance
- `SpecialistGovernanceService` admite un *probe* package-private, no configurable por API ni por LLM, que se ejecuta después de las escrituras de estado, decisión e idempotencia y **antes** del commit. El constructor público mantiene comportamiento anterior (no-op).
- `SpecialistGovernanceProcessWorker` soporta el modo exclusivo de pruebas `halt-in-transaction` (exit 33) para terminar abruptamente la JVM mientras SQLite mantiene una transacción abierta.
- `SpecialistGovernanceProcessRecoveryTest` verifica rollback de decisión/idempotencia/outbox, integridad del snapshot y reintento desde otra JVM que confirma una sola transición.
- El outbox debe instalarse explícitamente sobre una base de pruebas nueva; no hay migración automática, consumidor ni publicación.

### Validación y límites
- Ejecutar en Fedora `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceProcessRecoveryTest' --no-daemon --console=plain` y `./gradlew clean test --no-daemon --console=plain`.
- Este incremento prueba terminación abrupta de una JVM, **no** corte eléctrico real, garantía de fsync de hardware, carga sostenida, entrega del outbox ni reconciliación de bases corruptas.
- Antes de cerrar todo S1-C.3-C3 quedan pruebas de estrés multiproceso sostenido, recuperación bajo contención y runbook de integridad/restauración. Registrar resultados reales en el PR.
- Event Bus v1 intacto; selector v2 apagado; F4/G10-K sigue OPEN.

### Próximo incremento
- H01-B2.2: estrés multiproceso sostenido, fault injection con contención y procedimiento verificable de reconciliación/recuperación. No presentar B2.1 como cierre total de H01.

## H01-C1 — Contención sostenida entre JVM (implementación preparada; validación Fedora pendiente)

- Se incorpora `SpecialistGovernanceContentionTest`: 12 rondas independientes, 4 JVM por ronda, compitiendo con barrera de inicio por la misma revisión CAS del servicio real.
- Cada ronda inicializa explícitamente gobernanza y outbox, y exige exactamente una transición exitosa, una decisión, una clave de idempotencia y un evento outbox. Se verifica además el snapshot consistente.
- La prueba conserva logs por proceso para diagnóstico y termina los workers remanentes en `finally`. No modifica código productivo, endpoints, permisos, Event Bus v1 ni flags del selector v2.
- Validación pendiente en Fedora: `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceContentionTest' --no-daemon --console=plain`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Límite:** no representa carga de larga duración ni garantiza ausencia de starvation; si se detecta `SQLITE_BUSY`, clasificarlo y corregir la causa antes de cerrar. No se ha ejecutado Gradle en el entorno de generación.
- Siguiente H01-C2: crash-injection bajo contención, recuperación WAL y reconciliación de decisiones/idempotencia/outbox; después runbook y certificación integral. F4/G10-K sigue OPEN.

## H01-C2 — Crash-injection bajo contención (implementación preparada; validación Fedora pendiente)

- `SpecialistGovernanceProcessWorker` añade el modo de pruebas `halt-in-transaction-signaled`: escribe una señal tras completar las mutaciones dentro de la transacción y espera una señal de liberación antes de terminar la JVM con `halt(33)`. No se expone en REST, IA ni producción.
- `SpecialistGovernanceCrashContentionTest` crea tres bases independientes; por ronda, inicia una JVM que mantiene la transacción abierta, arranca una JVM competidora y provoca la caída de la primera. Comprueba un único commit de decisión, idempotencia y outbox, snapshot y outbox íntegros y rechazo CAS del comando abortado al reintentarlo tras el commit competidor.
- Se conservan el servicio productivo, Event Bus v1 y selector v2 deshabilitado. El test prueba caída de proceso, no corte eléctrico, durabilidad física, fairness de SQLite ni exactly-once en transporte.
- Validación pendiente: `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceCrashContentionTest' --no-daemon --console=plain` y `./gradlew clean test --no-daemon --console=plain`. Registrar evidencia antes del PR.
- Siguiente H01-C3: reconciliación diagnóstica de decisiones, idempotencia y outbox con pruebas de corrupción/ausencia y política fail-closed; después H01-C4 runbook y H01-C5 certificación integral. F4/G10-K permanece OPEN.


## H01-C3 — Reconciliación diagnóstica de gobernanza (implementación preparada; validación Fedora pendiente)

- `SpecialistGovernanceReconciler.verify(Path)` abre SQLite en modo read-only, usa una única transacción de lectura y verifica correspondencia entre decisiones, outbox, baseline e idempotencia.
- Detecta decisiones sin evento, eventos huérfanos, diferencias de campos, baseline huérfano y comandos sin decisión confirmada o con metadatos incompatibles.
- No exige que cada decisión tenga una clave idempotente: la API histórica permite comandos sin clave. No repara ni publica datos.
- Falla cerrado ante esquema ausente, corrupción o error SQL; la verificación **no está conectada aún** al gateway ni al selector.
- Tests nuevos de ausencia de base/esquema, comando huérfano y esquema vacío consistente.
- Validación local pendiente: `./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceReconcilerTest' --no-daemon --console=plain`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- Limitaciones: no demuestra integridad criptográfica contra manipulación coordinada, ni reparación segura, ni certifica un consumidor de outbox. F4/G10-K sigue OPEN; Event Bus v1 y selector v2 intactos.
- Próximo H01-C4: runbook de backup/restore y diagnóstico operacional; H01-C5: certificación integral.

## H01-C4 — Runbook de respaldo y recuperación SQLite (entrega preparada; validación pendiente)

- Se incorpora `scripts/aies-sqlite-recovery.py` con `backup`, `verify` y `restore` hacia **destinos nuevos exclusivamente**. Usa la API SQLite de backup compatible con WAL; no sobrescribe bases existentes ni ejecuta reparaciones.
- Se incorpora `docs/H01-C4-SQLITE-RECOVERY-RUNBOOK.md`: preservación de evidencia, procedimiento ante incidentes, restauración aislada, comprobaciones y autorización humana antes de un eventual cambio productivo.
- `PRAGMA integrity_check`, `foreign_key_check` y SHA-256 certifican integridad estructural de la copia; **no reemplazan** la reconciliación semántica H01-C3 ni la verificación de continuidad de estados.
- Pruebas Python de WAL, copia, rechazo de overwrite, corrupción y restauración aislada. Validar en Fedora con `python3 -m unittest discover -s tests/ops -p 'test_sqlite_recovery.py' -v`, `./gradlew clean test --no-daemon --console=plain` y `git diff --check`.
- Sin endpoints, activación de especialistas, cambios a Event Bus v1 ni selector v2. F4/G10-K sigue OPEN.
- Próximo H01-C5: certificación integral, incluyendo reconciliación H01-C3 sobre copia restaurada y evidencia de operación de recuperación.

## H01-C5 — Certificación integrada de restauración y reconciliación (pendiente de validación Fedora)

### Alcance entregado
- `SpecialistGovernanceRecoveryCertificationTest`: genera una base real mediante `SpecialistRegistryStore` y `SpecialistGovernanceService`, instala el outbox, ejecuta una transición autorizada e idempotente y verifica el estado durable.
- Ejecuta el script operativo **real** H01-C4 (`backup`, `verify`, `restore`) mediante un subproceso Python; no implementa una segunda ruta de backup.
- Compara el reporte H01-C3 antes y después de restaurar, comprueba `SpecialistGovernanceSnapshot` y `SpecialistGovernanceOutbox` sobre la copia, y verifica que la restauración no sobrescribe destinos.
- Prueba negativa: altera el actor del outbox en la copia restaurada; la estructura SQLite sigue siendo válida, pero el reconciliador detecta la inconsistencia y falla cerrado. Original y respaldo permanecen válidos.
- Prueba negativa: una base SQLite estructuralmente correcta pero sin migración de outbox se restaura; la reconciliación semántica rechaza el esquema incompleto.

### Validación obligatoria antes de cerrar el PR
```bash
./gradlew :apps:control-plane:test --tests '*SpecialistGovernanceRecoveryCertificationTest' --no-daemon --console=plain
python3 -m unittest discover -s tests/ops -p 'test_sqlite_recovery.py' -v
./gradlew clean test --no-daemon --console=plain
git diff --check
```

### Límites y estado
- **Implementación preparada; no se ejecutó Gradle en el entorno de generación.** Registrar los resultados de Fedora antes del commit/PR y corregir cualquier falla.
- La prueba valida recuperación de gobernanza con el servicio y el reconciliador reales, pero no simula pérdida física de disco, fsync de hardware, restauración sobre producción ni el consumidor del outbox. No garantiza exactly-once en transporte.
- No cambia producción, no añade endpoints ni activa selección v2; Event Bus v1 intacto. F4/G10-K sigue OPEN.
- H01 puede considerarse certificado para el **alcance local probado** después de superar todas las suites y revisar evidencia; la certificación operativa de recuperación ante desastre y el E2E real de F4 son trabajos separados.

### Próximo paso recomendado
- H02: unificación de integración runtime del registro gobernado con contratos y feature flags explícitos, después de realizar un inventario de rutas realmente conectadas y tests de compatibilidad v1/v2. No activar selector v2 sin autorización, métricas, rollback y pruebas de extremo a extremo.

## H02-A — Catastro verificable de integración (entrega preparada; validación Fedora pendiente)

- Nuevo `scripts/aies-integration-inventory.py`: inventario estático reproducible y **read-only** de clases relevantes, referencias Java y defaults de feature flags; no confunde presencia de código con conectividad en runtime.
- Nuevo `docs/architecture/H02-A-INTEGRATION-INVENTORY.md`: evidencia de rutas v1, planificación por perfiles, preview de capacidades y línea independiente de gobernanza; matriz de límites y plan H02-B a H02-F.
- Tests Python `tests/architecture/test_integration_inventory.py` para rutas ausentes, referencias y estabilidad del reporte.
- No se altera código productivo, Event Bus v1, selección v2, endpoints, scopes ni ejecución. F4/G10-K continúa OPEN.
- Validación local requerida: `python3 -m unittest discover -s tests/architecture -p 'test_*.py' -v`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`.
- Siguiente: **H02-B contrato de selección unificado y adaptadores de compatibilidad**, condicionado a revisar el informe generado contra el código y las rutas runtime reales. No eliminar componentes sin evidencia.

## H02-B — Contrato de selección común aislado (validación Fedora pendiente)

- El inventario H02-A recibido confirma referencias **estáticas**, no conectividad runtime: Event Bus v1, TeamPlanner, CapabilityRouter y registro gobernado permanecen rutas separadas.
- Se añade `SpecialistSelectionContract` como contrato Java puro de solicitud, candidato versionado, procedencia y decisión (`SELECTED`, `NO_MATCH`, `BLOCKED_SPECIALIST`, `NEEDS_CLARIFICATION`). El contrato no ejecuta especialistas ni concede herramientas.
- `legacyV1()` reproduce las cuatro entradas congeladas y se compara con `SpecialistCatalog.select` mediante tests de equivalencia para capacidades y presupuestos. No se altera Event Bus v1.
- La etiqueta `GOVERNED_V2` no autoriza ni valida estado: **no usar candidatos del registro sin filtro ACTIVE y verificación de integridad**. No se conecta todavía a SQLite, endpoints, planificador ni modelo.
- Validación local pendiente: `./gradlew :apps:control-plane:test --tests '*SpecialistSelectionContractTest' --no-daemon --console=plain`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`.
- Próximo H02-C: adaptador read-only gobernado, elegibilidad ACTIVE, feature flag separado apagado, fail-closed y pruebas de rollback/paridad antes de cualquier activación.
- F4/G10-K permanece OPEN. No hay certificación Quarkus/Ollama ni permisos implícitos.

## H02-C — Adaptador read-only del registro gobernado (implementación preparada; certificación Fedora pendiente)

- Se añade `GovernedSpecialistSelector`: adaptador Java aislado sobre `SpecialistSelectionContract`, con habilitación explícita por constructor. Deshabilitado devuelve `BLOCKED_SPECIALIST` sin tocar SQLite; habilitado lee SQLite en modo read-only y falla cerrado ante archivo/esquema ausente o integridad inválida.
- Verifica manifiestos **de todas las filas** (incluidas inactivas) con `SpecialistRegistryManifest.validate`: identidad, versión, JSON canónico y SHA-256. Sólo proyecta perfiles `ACTIVE` de origen `PROPOSAL`; excluye bootstrap legacy para evitar mezclar rutas v1/v2.
- Preserva capacidades, herramientas requeridas, coste y concurrencia del manifiesto; la selección no autoriza ejecución ni concede permisos. No se añade CDI, endpoint, consumer, nueva propiedad de configuración ni fallback a v1.
- `GovernedSpecialistSelectorTest` prueba modo deshabilitado sin creación de base, estado DRAFT, activación humana, herramientas, revocación, alteración de hash incluso en filas inactivas y aislamiento legacy.
- Validación requerida en Fedora: `./gradlew :apps:control-plane:test --tests '*GovernedSpecialistSelectorTest' --no-daemon --console=plain`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`. Registrar resultados reales en PR; **Gradle no ejecutado en el entorno de generación**.
- Límite: la selección no verifica cadena completa de auditoría/outbox ni revalida al despachar ejecución; no habilitar runtime todavía. F4/G10-K sigue OPEN.
- Siguiente **H02-D**: integrar el adaptador bajo flag independiente deshabilitado por defecto, autoridad explícita y chequeo en punto de uso; sombra, métricas, pruebas de rollback y compatibilidad. Después H02-E/F: reconciliación, E2E y eliminación de duplicaciones probadamente inactivas.

## H02-D1 — Fachada de selección explícita (implementación preparada; validación Fedora pendiente)

- `SpecialistSelectionFacade` introduce una frontera de política de selección con modos `LEGACY_V1` (predeterminado al construir mediante `legacy`) y `GOVERNED_V2` (elección explícita del llamador). Es una clase aislada: **todavía no existe cableado CDI, configuración runtime, endpoint ni consumidor Event Bus nuevo**.
- En modo legacy usa el contrato H02-B y sus cuatro candidatos congelados sin abrir SQLite. En modo gobernado delega al adaptador H02-C, propaga fallos de integridad/SQLite sin fallback a legacy y rechaza una asignación cuya procedencia no sea `GOVERNED_V2`.
- No concede herramientas ni autorización de ejecución. Las decisiones siguen siendo datos, no permisos. No cambia el selector v1 productivo ni activa v2.
- `SpecialistSelectionFacadeTest` comprueba aislamiento legacy, ausencia de fallback, procedencia gobernada y propagación de rechazos.
- **Validación local pendiente:** `./gradlew :apps:control-plane:test --tests '*SpecialistSelectionFacadeTest' --no-daemon --console=plain`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`. No declarar certificado hasta registrar resultados reales.
- **Siguiente H02-D2:** inspeccionar el punto de composición runtime, definir flag independiente apagado por defecto y autorización explícita, integrar fachada sin reemplazar Event Bus v1; añadir prueba de configuración/rollback, métricas y chequeo de integridad en punto de uso antes de cualquier activación. H02-E/F y F4/G10-K siguen pendientes.

## H02-D2A — Composición explícita de selección runtime (implementación preparada; certificación Fedora pendiente)

- Se incorpora `SpecialistSelectionRuntime` como frontera explícita de composición entre H02-D1 y H02-C. La configuración confiable `aies.specialists.governed-selection.enabled` se interpreta estrictamente: ausente/`false` conserva legacy; sólo `true` opta por gobernanza. Valores ambiguos se rechazan.
- El modo deshabilitado no abre ni crea SQLite; el modo habilitado usa `GovernedSpecialistSelector` y propaga fallos de base/integridad sin fallback silencioso. Una decisión de selección **no concede permiso de ejecución**.
- `SpecialistSelectionRuntimeTest` verifica paridad legacy, default apagado, rechazo de configuración ambigua, ausencia de creación de base y fail-closed ante registro ausente.
- Se añade el valor predeterminado apagado a `application.properties`, pero **esta clase aún no está inyectada en el consumidor productivo de Event Bus**. No declarar H02-D2 cerrado: la integración CDI/punto de uso, autenticación de operador, revalidación previa a despacho, métricas, sombra y rollback E2E son un siguiente incremento **H02-D2B**.
- Validación en Fedora: `./gradlew :apps:control-plane:test --tests '*SpecialistSelectionRuntimeTest' --no-daemon --console=plain`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Registrar resultados reales.
- Compatibilidad: Event Bus v1 intacto, ningún endpoint nuevo, selector v2 apagado por defecto, F4/G10-K OPEN.

## H02-D2B — Cableado Event Bus de selección bajo opt-in (entrega preparada; certificación Fedora pendiente)

- `SpecialistEventBus` utiliza `SpecialistSelectionBridge` en el consumidor **existente** `aies.specialists.select.v1`; no registra una segunda dirección ni cambia el contrato JSON (`selected`, `specialistId`, `costUnits`).
- La composición se efectúa al iniciar el consumidor y recibe configuración de operador, nunca del mensaje o del modelo. `aies.specialists.governed-selection.enabled=false` conserva los cuatro especialistas legacy sin tocar SQLite. Se agrega `aies.specialists.governed-selection.database` (`AIES_SPECIALISTS_GOVERNED_SELECTION_DATABASE`, predeterminado `./data/specialists.sqlite`), que debe apuntar a una base ya inicializada y aprobada por el operador.
- Con el flag `true`, el adaptador H02-C lee sólo perfiles ACTIVE y verifica manifiestos; ausencia/corrupción/errores de SQLite producen fallo del mensaje (503), **sin fallback** a legacy. Entrada inválida produce 400. La consulta SQLite se despacha con `executeBlocking`, no en el event loop.
- El endpoint de selección existente mantiene su autorización de operador y su flag `aies.specialists.enabled`; no se crea endpoint ni se habilita selección por defecto. Una selección no constituye autorización de ejecución ni habilita herramientas. **No se implementa aún revalidación al momento de despacho, métricas/sombra ni autorización gobernada end-to-end.** Por tanto, H02-D2 completo sigue OPEN y no se debe activar el flag v2 en producción.
- `SpecialistSelectionBridgeTest` cubre paridad de respuesta v1, ausencia de creación de SQLite, rechazo de flag inválido, solicitudes inválidas y fallo cerrado al faltar la base en modo v2.
- Validación obligatoria en Fedora: `./gradlew :apps:control-plane:test --tests '*SpecialistSelectionBridgeTest' --no-daemon --console=plain`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Registrar resultados reales antes de PR.
- Próximo **H02-D2C**: pruebas de integración Quarkus/Event Bus, revalidación de estado/hash en el punto de despacho, telemetría sin datos sensibles, modo sombra read-only, rollback probado y autorización explícita de operador. Luego H02-E/F, sin declarar F4/G10-K cerrado.

## V3-STRATEGY-01 — Dirección de producto: trabajo autónomo supervisado (especificación; implementación pendiente)

- **Decisión del propietario (2026-10-15):** AIES será inicialmente una organización digital de especialistas para su empresa personal, priorizando investigación y desarrollo de software con mínima carga de supervisión. La oferta SaaS a terceros es una opción posterior, condicionada a resultados internos medidos.
- Documento rector nuevo: `docs/architecture/AIES-AUTONOMOUS-WORK-ROADMAP.md`. Define versiones V3.0–V3.6 y V4.0 candidato, contratos propuestos, intake texto/JSON, etapa de comprensión, recuperación de fuentes y síntesis iterativa, DAG persistente de tareas atómicas, paralelismo limitado, gates, supervisión por excepciones, aprendizaje por workflow y presupuestos/stop conditions.
- **No es implementación:** ningún endpoint, flag, esquema SQLite, autorización ni consumidor se activa con este documento. Los contratos JSON son conceptuales; requieren ADR, pruebas y migración antes de usarse.
- **Prioridad técnica inmediata:** completar H02-D2C2 (revalidación en despacho), H02-D2C3 (métricas, shadow y rollback), H02-E/F (reconciliación, E2E y consolidación con evidencia), sin confundir selección con autorización de ejecución. F4/G10-K permanece OPEN.
- **Después:** V3.1 Understanding → V3.2 Context & Research → V3.3 Durable Task Graph → V3.4 Autonomous Software Engineer → V3.5 Human Inbox & Learning → V3.6 Personal Business Operations. V4.0 SaaS sólo tras piloto interno con métricas de calidad, costo y tiempo humano.
- **Consolidación:** reutilizar registro gobernado, Event Bus v1, planificación, orquestación, outbox, verificación y sandbox donde proceda. No retirar `TeamPlanner`, `CapabilityRouter` ni rutas duplicadas sin inventario runtime, pruebas de equivalencia, migración y rollback.
- **Validación de este hito documental:** verificar enlaces, consistencia con `main`, `git diff --check` y revisión humana del roadmap. No afirmar pruebas Gradle o E2E ejecutadas por esta actualización documental.

## AIES V3.2 — Ampliación de alcance: adquisición universal y enriquecimiento multimodal (diseño, NO implementado)

- Decisión: `docs/architecture/CONTEXT-ACQUISITION-ARCHITECTURE.md` especifica **Context Broker** accesible desde comprensión, planificación, ejecución, verificación y aprendizaje. Un modelo solicita contexto tipado; plataforma valida autorización, recursos y presupuesto, selecciona conectores, adquiere, procesa, resume y sintetiza evidencia. No depender de que el modelo abra enlaces por sí mismo.
- Separación obligatoria entre **conectores de origen** (HTTP/web, storage, GitHub, Google Docs/Drive, APIs, DB, local), **procesadores de contenido** (HTML/JSON/PDF/Office/código/imagen/audio/video), **Evidence Store**, templates versionados y **Sufficiency Gate**. Una fuente puede requerir varias rondas acotadas, con DAG acíclico y correlación durable.
- Alcance audiovisual: extracción/transcripción, fragmentos temporales, fotogramas, procedencia, privacidad y fallback explícito cuando no existe capacidad. No prometer acceso a recursos sin permisos.
- Seguridad: SSRF/egress, scopes mínimos, prompt injection, límites, revocación al punto de uso, no credenciales al modelo, no scraping que eluda controles. Aprendizaje operativo separado de recuperación de contexto; cambios a políticas/templates productivos con evaluación, aprobación y rollback.
- Reutilización de `modules:domain`, `modules:orchestration`, `adapters:sqlite`, `apps:control-plane`, Event Bus, gobernanza, scheduler y recovery **sujeta a inventario real**. `TeamPlanner` y `CapabilityRouter` siguen como baseline/preview hasta prueba de consumidores y equivalencia; no borrar por suposición.
- V3.2 se desglosa en V3.2.0 broker+HTTP/local, V3.2.1 procesadores+storage, V3.2.2 integraciones, V3.2.3 audiovisual, V3.2.4 síntesis iterativa. Ordenar vertical slices por utilidad, no construir todos los adaptadores antes del primer E2E.
- **Estado:** solo documentación; ninguna nueva clase, endpoint, configuración, migración o flag activado. H02-D2C2/C3, H02-E/F y F4/G10-K permanecen OPEN hasta evidencia específica. Validar `git diff --check` y revisión del ADR; Gradle no necesario para esta modificación documental.

## H02-D2C2 — Revalidación gobernada en worker antes de despacho (incremento preparado; certificación pendiente)

- Se inspeccionaron `ExecutionWorkerEngine`, `ExecutionWorkerEventBus`, `ExecutionDispatchEventBus`, `GovernedSpecialistSelector`, `SpecialistSelectionRuntime`, `SpecialistSelectionBridge` y pruebas del registro. La ruta de ejecución real usa `aies.execution.worker.run-one.v1` → `ExecutionWorkerEngine` → `aies.execution.specialist.execute.v1` o documentación; no se introduce un scheduler alternativo.
- El worker opt-in, cuando `aies.specialists.governed-selection.enabled=true`, instancia `GovernedSpecialistSelector` con configuración confiable y comprueba **después de reclamar la tarea y antes de llamar a un especialista** que el ID asignado sigue ACTIVE, su manifiesto íntegro contiene la capacidad de la tarea y no exige herramientas adicionales (el worker no transporta grants de herramientas). La lectura es read-only, valida todas las filas y falla cerrado ante corrupción o ausencia de SQLite. No se sustituye por otro especialista ni se usa fallback legacy. El worker no confía en el mensaje del modelo para el flag o ruta de DB.
- Si la comprobación falla, se usa la ruta durable de `fail` ya existente; el trabajo no se marca COMPLETED. La ruta legacy no se modifica cuando el flag está apagado. Se reutiliza la misma base/flag del selector H02-D2B, sin endpoint nuevo.
- Pruebas nuevas en `GovernedSpecialistSelectorTest`: estado DRAFT, activación, identidad y capacidad incorrectas, revocación, manifiestos con herramientas y base ausente. **No se ha ejecutado Gradle**: el wrapper intenta descargar Gradle 8.14.3 y el entorno no resuelve `services.gradle.org`.
- **Límites de seguridad pendientes:** el handoff actual persiste `specialistId` pero no fija `profileVersion` ni un `policySnapshot`; la comprobación por ID/capacidad no impide sustitución por otra versión ACTIVE del mismo ID. Existe una ventana TOCTOU entre consulta SQLite y la llamada al Event Bus; no hay autorización vinculada criptográficamente a un intento ni revalidación en el consumidor final. El flag gobernado NO debe activarse en producción todavía. La ruta de `ExecutionDispatchEventBus.CLAIM` tampoco está protegida por este incremento; no confundir claim con ejecución. Este hito es un guard parcial, **no cierre de H02-D2C2**.
- Próximo incremento **H02-D2C2b**: versionar/persistir identidad de perfil y snapshot de política en el handoff, revalidar autorización efectiva en el consumidor final justo antes de cualquier efecto y cerrar TOCTOU/revocación con pruebas de integración y compatibilidad; después H02-D2C3 shadow/métricas/rollback, H02-E/F. El Context Broker V3.2 reutilizará este patrón de autorización por recurso/acción, no permisos implícitos de selección. F4/G10-K OPEN.
- Validación Fedora: `./gradlew :apps:control-plane:test --tests '*GovernedSpecialistSelectorTest' --no-daemon --console=plain`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check` y pruebas E2E de ejecución bajo flag verdadero antes de cualquier activación.


## H02-D2C2b — Revalidación de identidad versionada en consumidor de inferencia (incremento preparado; validación Fedora pendiente)

- `GovernedSpecialistSelector` expone `AssignmentSnapshot` y `snapshotForDispatch` con coincidencia **única** de `specialistId`, versión y competencia, sin herramientas implícitas; `verifySnapshot` relee el registro gobernado y rechaza revocación, ambigüedad y cambio de versión. No constituye autorización de herramientas.
- `ExecutionWorkerEngine` propaga `governedSpecialistVersion` al mensaje interno únicamente si la gobernanza está habilitada; el modo legacy conserva el contrato anterior.
- `RealSpecialistEventBus` revalida la identidad en el consumidor real, inmediatamente antes de la inferencia Ollama, en worker thread, sin fallback; rechaza identidad ausente en modo gobernado y campo gobernado en modo legacy. La comprobación no es una transacción atómica con la inferencia; revocaciones posteriores al chequeo pueden coincidir con una llamada ya iniciada.
- **Límites y pendientes:** la versión se fija en el despacho, no en la selección original ni durablemente al crear el plan; el flujo alternativo `WorkspaceDocumentationEventBus.GENERATE` aún no pasa por este guard final. No se declara cerrado H02-D2C2b ni habilitada la gobernanza en producción. Próximo slice: pin durable de identidad/manifest hash por tarea e intento, autorización por herramienta en consumidores reales, cobertura de documentación y carreras de revocación, tests de integración Quarkus/SQLite.
- Validar en Fedora: `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. No se han ejecutado pruebas en el entorno de generación.


## H02-D2C2c — Guard gobernado en consumidor de documentación (incremento propuesto; pruebas Fedora pendientes)

- **Base inspeccionada:** `main` tras H02-D2C2b; se reutilizan `ExecutionWorkerEngine`, `WorkspaceDocumentationEventBus`, `WorkspaceDocumentationService`, `GovernedSpecialistSelector`, `ExecutionHandoffStore` y el Event Bus v1. No se crea scheduler, selector ni servicio de inferencia alternativo.
- **Cambio:** la ruta de documentación transporta `governedSpecialistId`, `governedSpecialistVersion` y `governedCapability` solamente cuando el modo gobernado está activo. El consumidor valida la identidad versionada contra el registro ACTIVE con manifiesto íntegro antes de iniciar el servicio. La documentación revalida en puntos sensibles: recuperación, preparación, reserva e invocación del modelo, y antes de materializar evidencia. El modo legacy rechaza campos de gobernanza inyectados, sin habilitar privilegios nuevos.
- **Pruebas agregadas:** contrato fail-closed de identidad ausente, ID/capacidad falsificados, inyección de campos gobernados en legacy y ausencia de SQLite. Requiere ejecutar `./gradlew clean test --no-daemon --console=plain` en Fedora; **no se afirma certificación** en el entorno de generación.
- **Limitaciones:** esta entrega NO implementa identidad durable fijada en la creación del plan ni un grant criptográfico por intento; la autorización de documentación existente sigue verificando concesión persistida, hash de fuente y lease. La revocación concurrente tras el último chequeo puede coincidir con una llamada ya iniciada; no existe atomicidad entre SQLite y HTTP Ollama. No activar gobernanza en producción por este cambio aislado. H02-D2C2 permanece OPEN.
- **Próximo slice obligatorio:** H02-D2C2d: pin durable por tarea/intento (id, versión, digest, policy revision), migración SQLite aditiva, autorización al punto de uso por herramienta/recurso, pruebas de reinicio y revocación concurrente; después H02-D2C3 observabilidad/shadow/rollback y H02-E/F. El DAG dinámico V3.3 y Context Broker V3.2 deberán consumir estas mismas políticas y evidencias, sin duplicar infraestructura.

## H02-D2C2d — Identidad durable por intento y auditoría SQLite del host (incremento para validación)

- **Base:** `main` posterior a H02-D2C2c, cotejado por SHA de archivos relevantes. Sin cambio de flags por defecto ni activación de ejecución gobernada.
- **Código:** `ExecutionHandoffStore` añade `execution_governed_attempts` (migración aditiva); vincula `(workflow,task,attempt)` con `(specialistId,version,capability)` solamente para un lease vivo. Reintentos no pueden cambiar versión silenciosamente. `ExecutionWorkerEngine` registra el vínculo antes del despacho. `RealSpecialistEventBus` y `WorkspaceDocumentationService` exigen que identidad y lease durable coincidan en puntos de uso, además de revalidar el registro gobernado. Fallo cerrado ante identidad/lease ausente, caducado o revocado.
- **Pruebas:** `ExecutionGovernedAttemptTest` cubre persistencia entre reaperturas, reintento, identidad falsificada, lease vencido y bloqueo de actualización silenciosa; mantener suite completa y pruebas live/negativas antes de habilitar flags.
- **SQLite del operador:** `scripts/aies-sqlite-inventory.py` y `tests/ops/test_sqlite_inventory.py` aportan inventario, `quick_check`, `foreign_key_check`, recuentos, backup online y cuarentena **solo opt-in** de bases desconocidas vacías. `docs/architecture/AIES-SQLITE-ESTATE-AND-NORMALIZATION.md` documenta propietarios, rutas y plan de cutover futuro. No se han recibido bases de datos reales: **ninguna DB de usuario se ha borrado, movido, renombrado o limpiado**.
- **Limitaciones pendientes:** el vínculo durable registra id/versión/competencia pero aún no fija digest de manifiesto y revisión de política en la misma transacción que el lease; comprobaciones de registro y ejecución no son una operación atómica frente a revocación concurrente; no hay cutover de ubicaciones de SQLite sin inventario del host. H02-D2C2 continúa parcial; no habilitar en producción.
- **Próximo incremento recomendado:** completar fingerprint de manifiesto + policy revision, autorización por herramienta/recurso, pruebas multi-JVM y revocación concurrente; H02-D2C3 observabilidad/shadow/rollback. Para DB: usar reporte real del host y aprobar por separado migración offline, deduplicación demostrada y cuarentena; evitar crear bases vacías por cambio unilateral de defaults.
- **Validación requerida:** `python3 -m unittest discover -s tests/ops -p 'test_sqlite_inventory.py' -v`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`, y reporte de inventario de las bases reales antes de cualquier limpieza.


## H02-D2C2e — Fingerprint durable de manifiesto y revisión del registro (incremento preparado)

- **Alcance:** extender el vínculo gobernado por intento con `manifest_sha256` y `registry_revision` verificados, además de ID/versión/competencia. El selector recupera el fingerprint del registro ACTIVE y los consumidores vuelven a contrastarlo en cada límite; una mutación de manifiesto o revisión invalida la asignación.
- **Migración:** dos columnas SQLite aditivas y nullable en `execution_governed_attempts`. Filas anteriores sin fingerprint **no se rellenan artificialmente** y fallan cerrado al intentar ejecutarse; requieren reconciliación humana. No se mueven ni eliminan bases de datos del operador.
- **Límites:** `registry_revision` es la revisión del perfil en el registro, **no** una revisión global de política de herramientas. La lectura del fingerprint y la selección no son una instantánea transaccional única; `verifySnapshot` vuelve a comprobar y falla cerrado ante cambios. No existe atomicidad entre revocación SQLite y llamada HTTP ya iniciada. No habilitar modo gobernado en producción por este incremento.
- **Pruebas:** identidad persistida, reinicio, intento falsificado, fingerprint ausente o cambiado y revisión diferente; exigir `./gradlew clean test --no-daemon --console=plain` en Fedora.
- **Próximo slice:** H02-D2C2f: política por herramienta/recurso con revisión propia, comprobación al punto de uso, coordinación de revocación y pruebas multi-JVM. Después H02-D2C3 observabilidad, H02-E/F consolidación, V3.1 comprensión, V3.2 Context Broker y V3.3 DAG dinámico reutilizando el worker/SQLite/Event Bus.

## H02-D2C2f — Primer slice: contrato puro de autorización granular (preparado para validación)

- Se introduce `GovernedToolResourcePolicy`, evaluador puro e inmutable para un intento concreto, herramienta, acción y recurso exacto. No crea permisos desde prompts, no concede comodines y rechaza revisiones obsoletas, identidades distintas, caducidad y revocación.
- La revisión `policyRevision` es **independiente** de `registryRevision` del especialista. El contrato admite cero permisos y deniega por defecto. Se incorporan cinco pruebas unitarias de coincidencia exacta, revisión, revocación, caducidad, identidad y entradas inválidas.
- **Límite importante:** el contrato no se conecta aún al worker, SQLite ni herramientas externas; no es un control de autorización operativo y no implica que exista una política persistida. No se activa ningún flag ni se altera la ejecución legacy. No se declara cerrado H02-D2C2f ni habilitada la gobernanza en producción.
- **Siguiente slice obligatorio:** definir propietario durable y revisión de política en SQLite; integrar el evaluador en un adaptador de herramienta real con carga confiable de política y autorización inmediatamente anterior al efecto; pruebas multi-JVM de revocación, expiración y recuperación. No reemplazar `ExecutionCapabilityAuthorization`, `ExecutionHandoffStore` ni los consumidores existentes sin probar equivalencia.
- Validar: `./gradlew :apps:control-plane:test --tests '*GovernedToolResourcePolicyTest' --no-daemon --console=plain`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`.

## H02-D2C2f.2 — Persistencia durable de autorización granular (slice aislado)

- **Base:** `main` posterior al contrato `GovernedToolResourcePolicy` (H02-D2C2f.1); instalación exige baseline del documento y árbol Git limpio.
- **Implementado:** `GovernedToolResourcePolicyStore` persiste una política inmutable por `(workflowId,taskId,attemptId)` en SQLite WAL, con concesiones exactas `(tool,action,resource)`, revisión de política independiente, expiración y revocación durable irreversible. `approve` solo acepta snapshots confiables; rechaza cambios y reactivación tras revocación. `evaluate` relee SQLite en cada llamada; deniega ausencia, revisión obsoleta, recurso no concedido, caducidad y revocación. `requireAuthorized` puede verificar también el lease/identidad gobernada existente de `ExecutionHandoffStore` antes de evaluar.
- **Pruebas:** `GovernedToolResourcePolicyStoreTest` cubre persistencia entre reaperturas, idempotencia exacta, aislamiento por recurso, revisión, revocación observada por conexiones independientes, prohibición de reactivación, inmutabilidad, caducidad e intento falsificado. Validar suite completa en Fedora.
- **Seguridad/limitaciones:** este slice **no conecta aún el store a los consumidores reales**, no expone endpoints de aprobación y no cambia flags. La aprobación debe provenir de un operador confiable; jamás del modelo. La comprobación de autorización y la operación externa no son atómicas; revocación concurrente no puede cancelar una llamada HTTP ya iniciada. La prueba de dos conexiones no constituye certificación multi-JVM. No habilitar gobernanza en producción.
- **SQLite:** esquema nuevo aditivo en el archivo que el consumidor confiable indique al construir el store; no cambia rutas ni limpia bases existentes. AIES_DATA_ROOT y migración física siguen pendientes de inventario del host.
- **Siguiente slice H02-D2C2f.3:** cablear `requireAuthorized` en un consumidor real con una operación concreta, derivar tool/action/resource del servidor (no del modelo), enlazar aprobación a autoridad confiable y registrar decisión; luego pruebas E2E de revocación y multi-JVM. Después H02-D2C3 observabilidad/shadow/rollback, H02-E/F y V3.1–V3.3.


## H02-D2C2f.3 — Primer consumidor real de autorización granular: documentación (incremento parcial)

- **Base:** `main` posterior a H02-D2C2f.2; verificación por Git blob SHA de los dos archivos Java modificados y del roadmap, con árbol Git limpio.
- **Integración real:** `WorkspaceDocumentationService` exige, solamente en modo gobernado, una política inmutable por intento en `governed-tool-policies.sqlite` para las operaciones exactas `workspace-documentation/read-source/<ruta real relativa>` antes de leer código y `workspace-documentation/invoke-model/ollama:software-engineer` antes de reservar la invocación. Herramienta, acción y recurso se derivan del servidor; no del modelo ni del Event Bus. Se reutiliza el vínculo de lease/identidad de `ExecutionHandoffStore` y la verificación de fingerprint. El modo legacy permanece intacto.
- **Store:** `requireAuthorizedCurrent` lee la revisión de la política inmutable y vuelve a verificar lease y estado durable en el punto de uso. No permite aprobar políticas ni se autoconcede permisos. La revocación posterior al último chequeo no cancela una operación externa ya iniciada.
- **Pruebas:** se añade prueba negativa de ausencia de política; ejecutar test focalizado y suite completa en Fedora. Las pruebas unitarias no constituyen certificación E2E de flujo gobernado.
- **Limitaciones / seguridad:** todavía NO hay flujo operador de provisión y aprobación de grants conectado al worker. Por diseño, documentación gobernada sin grants aprobados falla cerrada; no habilitar flag de gobernanza en producción. El consumidor de inferencia general no está cableado. La revisión de política es inmutable por intento pero no está persistida en el handoff de ejecución; falta decisión auditada durable y prueba multi-JVM real. No hay atomicidad SQLite/HTTP.
- **Próximo slice H02-D2C2f.4:** diseñar y conectar provisión confiable de grants mínimos, revisión vinculada al handoff, auditoría de decisiones, autorización en inferencia general y certificación E2E/multi-JVM de revocación; luego H02-D2C3 shadow/observabilidad/rollback. Mantener V3.1–V3.3 sobre los consumidores actuales.


## H02-D2C2f.4 — Gobernanza de inferencia genérica (incremento acotado)

- **Base:** `main` con H02-D2C2f.3 integrado. Este incremento protege `RealSpecialistEventBus` en modo governed mediante `GovernedInferenceBoundary`, antes de `OllamaSpecialistExecutor.execute`.
- **Permiso exacto:** `ollama / invoke-model / ollama:<specialistId>` derivado por el servidor; los campos `tool`, `action`, `resource`, `approved` recibidos por Event Bus nunca otorgan permisos.
- **Comprobaciones:** fingerprint/revisión del especialista existentes, identidad e intento durables, lease vigente y política SQLite operator-owned no revocada ni expirada. La ruta legacy no cambia. Falta de permiso => fail closed.
- **Pruebas:** `GovernedInferenceBoundaryTest` valida derivación de recurso, denegación sin política y rechazo de identidad incompleta. Ejecutar suite completa en Fedora.
- **Limitaciones:** **no se implementan** todavía el flujo de aprobación humana, la vinculación durable de revisión de política al handoff, auditoría estructurada, ni certificación multi-JVM/E2E. La comprobación no cancela llamadas HTTP ya iniciadas. No habilitar governed en producción.
- **Siguiente slice H02-D2C2f.5:** autoridad confiable de aprobación y revocación, fijación inmutable de revisión en el handoff, registro durable de decisiones, pruebas multi-proceso y E2E con revocación. Después H02-D2C3, H02-E/F, V3.1–V3.3.


## H02-D2C2f.5 — Provisión de políticas por operador (slice 1: endpoint opt-in)

- **Base:** `main` posterior a H02-D2C2f.4. Nuevo `GovernedPolicyOperatorResource` con endpoints `POST /api/v1/execution/governed-policies/approve` y `POST /api/v1/execution/governed-policies/{workflowId}/{taskId}/{attemptId}/revoke`.
- **Autoridad:** ambos endpoints reutilizan `WorkspaceOperatorAuthorizer` y están cerrados por defecto mediante `aies.execution.governed-policy-operator.enabled=false`. Requieren credencial del operador; jamás aceptan un mensaje Event Bus como aprobación.
- **Aprobación:** el operador proporciona identidad completa, capability, fingerprint, revisión de registro, revisión de política, caducidad y grants exactos. Antes de persistir se verifica el `GovernedAttempt` en `ExecutionHandoffStore`; `GovernedToolResourcePolicyStore` conserva inmutabilidad, idempotencia y denegación por defecto. Revocación durable e irreversible.
- **Pruebas:** test del contrato de grants y snapshots; se requiere validar en Fedora la suite completa y probar manualmente con token válido e inválido, intento inexistente y revocación.
- **Limitaciones:** este slice **NO** implementa todavía la vinculación inmutable de revisión al handoff, auditoría durable de cada decisión, autenticación diferenciada por roles, tests E2E reales, ni certificación multi-JVM. `WorkspaceOperatorAuthorizer` reutiliza el token configurado para workspace changes. No habilitar producción hasta cerrar estas brechas.
- **Siguiente slice H02-D2C2f.6:** persistir revisión de política en handoff, añadir auditoría estructurada y pruebas de revocación multi-proceso/E2E; después H02-D2C3.

## H02-D2C2f.6 — Vinculación durable de revisión al intento (slice 1)

- **Baseline:** `main` tras H02-D2C2f.5. Migración SQLite aditiva: `execution_governed_attempts.policy_revision` nullable para preservar filas antiguas; filas sin revisión vinculada fallan cerradas al ejecutar herramientas.
- **Aprobación:** el endpoint de operador aprueba una política inmutable y vincula su revisión al intento vigente. Un fallo entre ambas escrituras (dos archivos SQLite distintos) deja una política persistida pero **no ejecutable**; un replay idéntico puede completar el vínculo. No se declara atomicidad distribuida.
- **Consumidores:** `GovernedToolResourcePolicyStore.requireAuthorized` verifica ahora revisión persistida en `ExecutionHandoffStore` además de lease, identidad, expiración, revocación y grants exactos. La ruta legacy no cambia.
- **Concurrencia:** el vínculo usa actualización condicional `WHERE policy_revision IS NULL`; revisiones diferentes no sustituyen el valor ya fijado. Falta certificación multi-JVM y auditoría durable de cada decisión.
- **Pruebas:** añadir pruebas unitarias de migración, replay, mismatch y revocación en el siguiente slice; ejecutar suite Gradle completa antes de fusionar. No habilitar gobernanza en producción.
- **Siguiente slice H02-D2C2f.7:** journal durable de decisiones y revocaciones, integración de auditoría en ambos consumidores, HTTP/E2E real y multi-proceso; después H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.7 — Journal durable de políticas (slice 1)

- **Baseline:** `main` tras H02-D2C2f.6. Migración aditiva en `governed-tool-policies.sqlite`: tabla `governed_policy_audit` e índice por intento.
- **Eventos:** `APPROVE`, `REVOKE`, `EVALUATE` con revisión, identidad de intento, herramienta/acción/recurso, decisión y razón; no se guardan tokens ni prompts.
- **Atomicidad local:** aprobación y revocación se auditan dentro de la misma transacción SQLite. `evaluate` persiste su decisión antes de devolverla; si falla la escritura, no se devuelve ALLOW. Las operaciones externas no son transaccionales.
- **Consultas:** API interna `auditTrail` ordenada por `event_id`. No se expone endpoint público para evitar fugas de información.
- **Pruebas:** `GovernedPolicyAuditJournalTest` verifica persistencia tras reinicio, replay, ALLOW, DENY, revisión incorrecta, revocación y ausencia de política.
- **Limitaciones:** la verificación de identidad/lease y el enlace de revisión ocurren en otro store y todavía no se auditan como eventos DENY; no se certifica la revocación durante una llamada externa iniciada. Pendiente integración E2E HTTP y pruebas multi-JVM. No habilitar governed en producción.
- **Siguiente slice H02-D2C2f.8:** auditar todas las denegaciones pre-evaluación, pruebas HTTP de credenciales y permisos, migración/replay multi-proceso y certificación E2E. Después H02-D2C3.

## H02-D2C2f.8 — Auditoría de denegaciones tempranas (slice 1)

- Baseline: `main` tras H02-D2C2f.7.
- `GovernedToolResourcePolicyStore` registra eventos `PRECHECK/DENY` para política inexistente, identidad de intento incorrecta y rechazo de la validación del handoff (lease/revisión/identidad).
- Los motivos de handoff se agrupan en `HANDOFF_REJECTED` para no filtrar detalles de estado interno. La escritura del journal es obligatoria y no convierte una denegación en autorización.
- Pruebas `GovernedPolicyEarlyDenialAuditTest`: falta de política, identidad incorrecta, intento sin revisión vinculada, persistencia del registro.
- **Alcance limitado:** las excepciones SQL/IO durante el handoff no se clasifican como denegaciones de seguridad; siguen abortando la ejecución. No hay certificación HTTP ni multi-JVM ni E2E en este slice. No activar en producción.
- Próximo slice H02-D2C2f.9: pruebas HTTP del operador, recuperación, migración, concurrencia y E2E real. Luego H02-D2C3.

## H02-D2C2f.9 — Certificación HTTP negativa y concurrencia SQLite (slice 1)

- Baseline: `main` tras H02-D2C2f.8.
- Tests Quarkus HTTP reales: `GovernedPolicyOperatorHttpDisabledTest` verifica feature flag cerrado por defecto para aprobación y revocación. `GovernedPolicyOperatorHttpEnabledTest` verifica rechazo de credenciales ausentes o erróneas y solicitudes inválidas con credencial válida.
- Test de concurrencia `GovernedPolicyConcurrentStoreTest`: dos conexiones SQLite intentan aprobar revisiones contradictorias; solamente una puede persistir y queda un evento `APPROVED`.
- No se modifican los contratos de producción. Los tests HTTP del perfil habilitado no aprueban políticas válidas ni crean leases; las respuestas 409 son esperadas.
- **Limitaciones:** falta E2E HTTP positivo con intento/lease reales, certificación multi-JVM, migraciones/replay y escenarios de crash entre aprobación y vinculación. No se declara finalizada H02-D2C2f.9 ni se recomienda habilitar governed en producción.
- **Próximo incremento H02-D2C2f.10:** E2E positivo, recuperación de fallos, multiproceso y cierre de certificación. Después H02-D2C3.

## H02-D2C2f.10 — Recuperación entre dos stores SQLite (slice 1)

- Baseline: `main` tras H02-D2C2f.9.
- Test de integración con `ExecutionHandoffStore` y `GovernedToolResourcePolicyStore` reales: plan, lease, identidad, aprobación, cierre/reapertura entre aprobación y binding, DENY antes del vínculo, replay idéntico, binding, ALLOW y revocación persistente.
- Inmutabilidad: revisión alternativa y sustitución de política rechazadas. Journal verificado tras reinicio.
- No hay cambios en código de producción. Validar test focalizado y suite completa en Fedora.
- **Limitaciones:** cierre/reapertura simula la ventana de crash, pero no equivale a kill -9. Pendientes HTTP positivo, multi-JVM y migración histórica; no habilitar governed en producción.
- **Próximo H02-D2C2f.11:** E2E HTTP positivo, multiproceso y migraciones. Después H02-D2C3.

## H02-D2C2f.11 — Certificación HTTP positiva con lease real (slice 1)

- Baseline: `main` después de H02-D2C2f.10.
- Test `GovernedPolicyOperatorHttpPositiveTest`: Quarkus HTTP real y RestAssured con perfil aislado, token de operador, directorio temporal exclusivo y dos stores SQLite reales.
- Flujo: importar plan, reclamar lease, vincular identidad y fingerprint, aprobar vía HTTP, repetir aprobación idéntica, verificar autorización ALLOW, revocar vía HTTP, comprobar DENY durable y rechazar reactivación HTTP.
- No se modifican contratos de producción. Se verifica persistencia mediante reapertura de conexiones y journal de revocación.
- **Limitaciones:** todavía no hay certificación multi-JVM ni kill -9, migración histórica ni integración E2E de consumidores. El test usa HTTP real contra Quarkus embebido, no despliegue externo. No declarar finalizada H02-D2C2f ni habilitar gobernanza en producción.
- **Próximo H02-D2C2f.12:** multiproceso JVM, interrupción abrupta, migraciones y consumidores; después H02-D2C3.


## H02-D2C2f.12 — Certificación multiproceso JVM (slice 1, pendiente de validación Fedora)

- Baseline: `main` posterior a H02-D2C2f.11, SHA `0bd1ff2feee85bff09ffbe15c40da1b582a104ba`.
- Pruebas nuevas: `GovernedPolicyMultiJvmIntegrationTest` ejecuta dos JVM independientes con `ProcessBuilder` y classpath real de Gradle; la coordinación de inicio utiliza archivos barrera temporales y límites de tiempo explícitos.
- Garantías bajo prueba: dos aprobaciones contradictorias sobre la misma política SQLite solo admiten un ganador; autorización ALLOW en JVM separada, revocación desde otra JVM y DENY persistente en una tercera, con journal durable.
- Sin cambios en rutas SQLite del operador, flags de producción, permisos, modelo de política o código de aprobación.
- **Límites:** este slice no prueba aún `kill -9`, migraciones históricas, ni E2E completo de consumidores; no se declara cerrado H02-D2C2f ni se habilita gobernanza en producción. SQLite WAL multiproceso presupone filesystem local compatible, no NFS.
- **Validación obligatoria:** test focalizado multiproceso con `--rerun-tasks`, suite completa `./gradlew clean test --no-daemon --console=plain`, `git diff --check`. Si aparecen errores de locking o driver, preservar XML y corregir la causa, no añadir reintentos arbitrarios.
- **Siguiente slice H02-D2C2f.12b:** crash real `kill -9` entre aprobación y binding, migración histórica verificable y límites E2E de consumidores; después H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12b — Crash abrupto y migración histórica aditiva (slice 2; pendiente de validación Fedora)

- **Baseline:** `main` después de H02-D2C2f.12, SHA `e8cffe64179d22a3341bc0467fcd214f9f3e7914`.
- **Prueba real de proceso:** `GovernedPolicyCrashRecoveryTest` lanza una JVM hija independiente, persiste aprobación en `governed-tool-policies.sqlite`, cierra la conexión, escribe una barrera `approved.ready` y espera. El proceso padre fuerza la terminación con `Process.destroyForcibly()` (equivalente a SIGKILL en Fedora/Linux), comprueba salida anormal y reabre ambos SQLite.
- **Invariantes:** política aprobada pero sin `policy_revision` enlazada al handoff = DENY; replay idéntico no autoriza hasta vinculación; revisión distinta rechazada; vínculo exacto habilita ALLOW; revocación irreversible sigue DENY tras nueva reapertura; journal conserva exactamente una aprobación inicial y el replay.
- **Migración histórica acotada:** `GovernedPolicyLegacyMigrationTest` construye una fixture anterior a `manifest_sha256`, `registry_revision` y `policy_revision`. La apertura del store añade columnas nullable sin inventar datos; conserva la fila histórica y soporta reapertura idempotente. No se abre ni modifica ninguna base real del operador.
- **Sin cambios de producción:** no se altera la política de permisos, endpoints, flags, rutas de bases, ni los mecanismos de aprobación. Se amplía exclusivamente el harness de pruebas multiproceso y se añaden tests.
- **Limitaciones explícitas:** `destroyForcibly()` se ejecuta sobre un hijo detenido *después* del commit de aprobación, no en medio de una transacción SQLite; no certifica crash durante commit, corrupción física, pérdida de energía ni la totalidad de esquemas históricos. Tampoco certifica el E2E completo de consumidores reales ni la revocación de llamadas HTTP ya iniciadas. SQLite WAL presupone almacenamiento local compatible. No habilitar gobernanza en producción.
- **Validación:** `./gradlew :apps:control-plane:test --tests '*GovernedPolicyCrashRecoveryTest' --tests '*GovernedPolicyLegacyMigrationTest' --no-daemon --console=plain --rerun-tasks`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`; ejecutar en Fedora y conservar reportes ante fallos.
- **Próximo slice H02-D2C2f.12c:** certificación de consumidores reales (documentación e inferencia), escenarios de interrupción durante transacción y fixtures de migración representativas de inventario real aprobado. Después H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12c — Certificación del consumidor real de inferencia (slice 1; pendiente de validación Fedora)

- **Baseline:** `main` SHA `4e8c915c24a50e0746bb46c1ec0bc43ff7376c25`, posterior a H02-D2C2f.12b.
- **Implementado:** `GovernedInferenceConsumerIntegrationTest` llama al límite de producción `GovernedInferenceBoundary.require` con dos stores SQLite reales, lease reclamado, identidad/fingerprint vinculados y revisión de política aprobada por el test (simulación de operador confiable). Comprueba DENY sin política, DENY antes de binding, ALLOW tras binding, resistencia a campos de autorización falsificados en Event Bus, DENY por fingerprint/intento falso, revocación persistente, replay revocado rechazado y auditoría durable.
- **Alcance:** se prueba el límite previo a invocar Ollama, no se lanza Ollama ni se ejercita el Event Bus Quarkus de extremo a extremo. El test no reemplaza la prueba de integración HTTP del operador ni la certificación completa de `WorkspaceDocumentationService`. No hay cambios en producción, permisos, rutas o flags.
- **Seguridad:** grants `tool/action/resource` se derivan en servidor; campos `approved`, `tool`, `action`, `resource` del mensaje no autorizan. Las verificaciones de identidad, lease, revisión y revocación fallan cerradas. No existe atomicidad entre la comprobación y una llamada HTTP externa ya iniciada.
- **Validación obligatoria:** `./gradlew :apps:control-plane:test --tests '*GovernedInferenceConsumerIntegrationTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Conservar reportes XML ante fallos; no declarar certificación hasta validación real en Fedora.
- **Siguiente H02-D2C2f.12d:** E2E del consumidor de documentación con selector gobernado real, mock HTTP local de Ollama y contadores de efectos; inyección de fallos en journal/SQLite; reconciliación de migraciones con inventario del operador. Después H02-D2C3 observabilidad/shadow/rollback. Mantener gobernanza deshabilitada en producción.

## H02-D2C2f.12d — Certificación E2E del consumidor documental (slice 1; validación Fedora pendiente)

- **Baseline:** `main` SHA `7bd403fe3c5aeb2914ebebf55b80bf675546b313`, posterior a H02-D2C2f.12c.
- **Implementación:** `GovernedDocumentationConsumerE2ETest` integra `WorkspaceDocumentationService`, perfil `software-engineer` propuesto y activado por principal humano en el registro SQLite, snapshot verificable, lease real, autorización documental durable, política de recursos inmutable y servidor HTTP Ollama simulado con contador de invocaciones.
- **Pruebas:** falta de grant `read-source` => DENY y cero llamadas; grant de lectura sin `invoke-model` => DENY antes de HTTP; ambos grants => documento Markdown, artefacto durable y exactamente una invocación; replay devuelve evidencia sin segunda invocación; revocación impide nuevo replay y no causa nueva llamada.
- **Seguridad:** se reutilizan límites reales del consumidor y stores; ningún campo `approved` concede permisos. Sin modificación de código de producción, flags ni permisos predeterminados. El mock HTTP usa loopback y puerto efímero; las bases están aisladas en `@TempDir`.
- **Límites:** esta prueba invoca directamente el servicio, no el Event Bus Quarkus completo; no simula fallo de escritura/auditoría ni revocación durante HTTP ya iniciado. El conteo HTTP certifica efectos de inferencia, no demuestra que un archivo no haya sido leído en memoria. No se habilita gobernanza en producción.
- **Validación:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`. No declarar PASS hasta ejecutar en Fedora.
- **Siguiente H02-D2C2f.12e:** Event Bus real, inyección de fallos de journal y SQLite, carreras concurrentes y validación de no-efectos externos; luego H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12e — Concurrencia documental y denegación por falta de política (slice 1; validación Fedora pendiente)

- **Baseline:** `main` SHA `bc340fb7d1257bb441058355ec458d4870f5cdff`, posterior a H02-D2C2f.12d.
- **Implementación acotada:** se amplía `GovernedDocumentationConsumerE2ETest` con dos entregas concurrentes sobre el mismo intento gobernado, coordinadas por `CountDownLatch`, y con un caso de SQLite de política ausente. Se reutilizan selector activado, handoff real, política operator-owned, servidor HTTP loopback y contador de invocaciones.
- **Criterios:** concurrencia no produce más de una invocación HTTP por intento; al menos una entrega completa; base de políticas ausente no concede permisos ni invoca Ollama; no se publica documentación en el caso denegado.
- **Límites:** la concurrencia se ejerce directamente sobre `WorkspaceDocumentationService`, no mediante `WorkspaceDocumentationEventBus` en Quarkus. La ausencia de SQLite no representa corrupción durante commit ni fallo de auditoría después de autorización. Una invocación HTTP ya iniciada no puede revocarse retroactivamente. Este slice NO certifica el cierre de H02-D2C2f ni habilita gobernanza en producción.
- **Validación obligatoria:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`. Revisar reportes JUnit XML y resolver flakiness antes de PR.
- **Siguiente slice H02-D2C2f.12f:** Event Bus Quarkus real, autorización bajo carreras de revocación y fallo transaccional controlado, con pruebas negativas y auditoría. Después revisión de cierre de H02-D2C2f y H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12f — Event Bus Vert.x y falla de auditoría (slice 1; pendiente de validación Fedora)

- **Baseline:** `main` SHA `199c88f773a6a2b5a3807ec3f82cca54f55705d2`, posterior a PR #121 (12e).
- **Implementado:** se amplía `GovernedDocumentationConsumerE2ETest` para entregar solicitudes por el `WorkspaceDocumentationEventBus` real sobre un `Vertx` local, configurando el componente explícitamente sin iniciar Quarkus CDI. La prueba positiva invoca el mock Ollama exactamente una vez; tras revocar la política, una nueva entrega por Event Bus se rechaza y no produce una segunda invocación. La prueba negativa inyecta un trigger SQLite que aborta el INSERT de auditoría `ALLOW`: la entrega falla y no llega a HTTP ni publica documentación.
- **Seguridad:** no se habilita ningún flag por defecto ni se modifican clases de producción. Los campos de identidad enviados por Event Bus se verifican contra registro, lease y grants persistidos; los errores del consumidor se devuelven como fallos de entrega.
- **Límites:** no se arranca Quarkus/CDI ni se certifica el despliegue completo del control-plane; no hay fallo durante commit, ni carrera de revocación con HTTP en vuelo. La falla de auditoría es una inyección SQL determinista, no una simulación de pérdida de energía. El contador HTTP solo prueba ausencia de invocaciones al modelo. H02-D2C2f sigue abierto; gobernanza deshabilitada en producción.
- **Validación:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Ejecutar en Fedora antes del PR.
- **Siguiente H02-D2C2f.12g:** Quarkus CDI real, carreras controladas de revocación e inyección de fallos durante transacción, auditoría y reconciliación; posteriormente revisión de cierre y H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12g — Event Bus concurrente y recuperación de fallo de auditoría (slice 1; pendiente de validación Fedora)

- **Baseline:** `main` SHA `113e911f11978d927e67bb54f489571c6819d358`, posterior al PR #122 (12f).
- **Implementación:** dos pruebas sobre `GovernedDocumentationConsumerE2ETest`, reutilizando `WorkspaceDocumentationEventBus`, Vert.x local, registro gobernado activado, lease, políticas SQLite y Ollama HTTP simulado. La primera provoca un fallo determinista en el INSERT de auditoría `ALLOW`, verifica cero HTTP y ausencia de reserva de efecto, elimina el trigger y certifica recuperación mediante reintento y replay sin duplicación. La segunda envía dos entregas simultáneas por el Event Bus real y verifica como máximo una llamada HTTP.
- **Garantías verificables:** fallo de auditoría antes de autorización no concede permisos ni consume reserva de modelo; reintento tras reparación permite una ejecución; replay no duplica HTTP; concurrencia por Event Bus mantiene la reserva durable por intento.
- **Límites:** el consumidor se configura mediante harness Vert.x, sin arranque completo de Quarkus/CDI; el fallo se inyecta en el INSERT de auditoría, no durante COMMIT ni tras el envío HTTP; no se afirma cancelación de HTTP ya iniciado, exactamente-once distribuido ni cierre de H02-D2C2f. Gobernanza continúa deshabilitada por defecto. Sin cambios de producción.
- **Validación obligatoria:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`; comprobar reportes JUnit antes de PR.
- **Siguiente H02-D2C2f.12h:** integración Quarkus/CDI mediante `@QuarkusTest` con configuración aislada, fallos transaccionales post-reserva, carrera revocación/HTTP con barreras deterministas y reconciliación explícita de estados ambiguos. Después revisión formal de cierre H02-D2C2f y transición a H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12h — Revocación durante HTTP y reserva ambigua durable (slice 1; validación Fedora pendiente)

- **Baseline:** `main` SHA `eb1d9c0ad533f024338606c9d1de6114d038890f`, posterior al PR #123 (12g).
- **Implementación:** dos pruebas de integración en `GovernedDocumentationConsumerE2ETest`. Un servidor HTTP loopback mantiene la respuesta pendiente mediante `CountDownLatch`; el operador revoca la política mientras HTTP está en vuelo y, tras liberar la respuesta, el servicio rechaza la publicación, mantiene reserva durable ambigua y no reintenta. Otro caso simula una reserva persistida antes de recibir respuesta: la recuperación rechaza una segunda invocación sin tocar HTTP.
- **Garantías acotadas:** revocación posterior al envío no cancela el efecto HTTP externo, pero impide publicación del resultado y posteriores reintentos; la reserva ambigua se conserva como `AMBIGUOUS_INVOCATION`; ninguna ejecución duplicada automática.
- **Límites:** sin arranque CDI de Quarkus ni `@QuarkusTest`; sin crash real del proceso ni fallo inyectado durante COMMIT SQLite; las pruebas usan servicio real, SQLite y HTTP mock, no un runtime distribuido. No se declara cierre de H02-D2C2f ni exactamente-once distribuido. Flags de gobernanza permanecen deshabilitados por defecto. Sin cambios de producción.
- **Validación Fedora obligatoria:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Inspeccionar JUnit y flakiness antes de PR.
- **Siguiente H02-D2C2f.12i:** `@QuarkusTest` con CDI y configuración aislada real, inyección transaccional post-reserva y pruebas de recuperación tras reinicio; matriz de cierre de H02-D2C2f antes de H02-D2C3 (observabilidad, shadow y rollback).

### H02-D2C2f.12h — Corrección de seguridad detectada durante validación Fedora

- **Hallazgo:** la prueba de revocación durante HTTP identificó que `WorkspaceDocumentationService` revalidaba el intento gobernado después de recibir la respuesta del modelo, pero no volvía a comprobar la política operator-owned. Una revocación durante la invocación podía permitir continuar hacia la publicación.
- **Corrección de producción:** se incorporó una nueva llamada a `authorizeGovernedTool` después de `model.execute(request)` y antes de crear los archivos de salida. La autorización consulta nuevamente la política SQLite y registra la decisión de auditoría.
- **Garantía certificada:** cuando la política se revoca mientras el servidor HTTP simulado retiene la respuesta, la operación es denegada antes de publicar el documento. La reserva durable permanece registrada y no se repite automáticamente la invocación.
- **Validación Fedora:** prueba focalizada `BUILD SUCCESSFUL` (12 s); suite completa `BUILD SUCCESSFUL` (32 s).
- **Límite residual:** esta comprobación no elimina completamente la carrera entre la última autorización y la publicación material. El cierre de esa ventana requiere coordinación transaccional o un mecanismo de fencing adecuado. Tampoco certifica el arranque completo Quarkus/CDI.
- **Próximo incremento H02-D2C2f.12i:** integración Quarkus/CDI, pruebas de recuperación tras reinicio, fallos transaccionales y diseño de fencing para publicación.

## H02-D2C2f.12i — Quarkus/CDI real: registro de consumidor y límites de confianza (slice 1; validación Fedora pendiente)

- **Baseline:** `main` SHA `94fbc7d914b07bb0ac56d53cfd9e087f1ac420f0`, posterior al PR #124 (12h).
- **Alcance:** nuevo `WorkspaceDocumentationQuarkusLifecycleTest` con `@QuarkusTest` y `QuarkusTestProfile`. Arranca el control-plane con CDI real, habilita exclusivamente el consumidor de documentación en el perfil de prueba y mantiene gobernanza deshabilitada. Verifica la inyección del bean, la entrega por Event Bus real y el rechazo de solicitudes inválidas con `ReplyFailure.RECIPIENT_FAILURE`/422, diferenciando ese rechazo de `NO_HANDLERS`.
- **Límite de confianza:** campos de identidad gobernada enviados cuando la gobernanza está deshabilitada se rechazan en el consumidor real; nunca se consideran concesiones. No se invoca HTTP externo ni se prepara un intento de ejecución en estos escenarios negativos.
- **Cambios:** solo pruebas y este documento. Sin cambios de producción, defaults, esquema SQLite, HTTP ni contratos de autorización.
- **Validación requerida:** `./gradlew :apps:control-plane:test --tests '*WorkspaceDocumentationQuarkusLifecycleTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. No se afirma certificación hasta obtener resultados Fedora y revisar JUnit XML.
- **Límites pendientes:** no hay prueba positiva de documentación gobernada bajo CDI, aislamiento multi-perfil, fallos durante COMMIT SQLite, reinicio de proceso ni fencing transaccional entre autorización y publicación. La autorización post-HTTP de 12h reduce la ventana de revocación, pero no la elimina. H02-D2C2f continúa abierto; gobernanza permanece deshabilitada por defecto.
- **Siguiente H02-D2C2f.12j:** E2E positivo CDI con workspace/SQLite/HTTP aislados, protocolo de publicación con fencing y ADR sobre atomicidad SQLite/sistema de archivos; posteriormente inyección de fallos, recuperación y matriz formal de cierre antes de H02-D2C3 observabilidad/shadow/rollback.

## H02-D2C2f.12j — Post-HTTP audit fail-closed and publication-fencing ADR (bounded slice; Fedora validation pending)

- **Baseline:** `main` SHA `04221228a64bd868d1f0123b6e29507d42300db1`, merge PR #125 (12i).
- **Implementation:** deterministic regression test `postHttpAuditFailureDeniesPublicationAndPreservesAmbiguousReservation` in `GovernedDocumentationConsumerE2ETest`; SQLite trigger fails the third `ALLOW` audit insert, after the model HTTP call. Asserts no generated-docs, durable `AMBIGUOUS_INVOCATION`, and no duplicate HTTP after fault removal. No production code or default flag changes.
- **ADR:** `docs/adr/H02-D2C2f-12j-publication-fencing.md` records the current cross-store TOCTOU gap, the required revocation/publication linearization point, crash recovery invariants and outstanding design decisions. ADR is proposed, not implemented.
- **Validation:** Fedora must run `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`, `./gradlew clean test --no-daemon --console=plain`, and `git diff --check`; review JUnit failures before PR.
- **Unresolved:** full positive governed CDI integration, publication fence implementation, SQLite COMMIT/crash injection and multi-process restart recovery. No claim of exactly-once distributed execution or closure of H02-D2C2f.
- **Next H02-D2C2f.12k:** implement and test a durable publication admission/fence coordinated with revocation, then recover staging/rename/artifact phases under controlled crashes and validate a positive governed CDI path.

## H02-D2C2f.12k — Durable publication admission (bounded first production slice; Fedora validation pending)

- **Baseline:** main `14d51cffdd7b283b7d4768c9632064e8b91f6d3f`, PR #126 merged.
- **Production:** new `governed_publication_admissions` table in operator policy SQLite; `admitPublication` uses `BEGIN IMMEDIATE`, reads current exact policy and writes admission plus ALLOW audit in the same transaction. Revocation uses the same SQLite writer authority. `WorkspaceDocumentationService` calls admission after HTTP and before staging, only for governed mode. Legacy mode unchanged.
- **Tests:** new `GovernedPublicationAdmissionTest` covers admission durability across restart, revocation-before-admission denial, revocation-after-admission ordering and missing policy. Run targeted and full Gradle tests on Fedora before PR.
- **Explicit limitations:** this is NOT a complete publication state machine; `staging`, atomic rename, communication append and execution journal remain outside the policy transaction. Recovery paths are not yet fenced. Existing post-HTTP grant revalidation remains. No exactly-once distributed guarantee, no process crash/COMMIT fault certification, no positive governed CDI E2E. No default feature flag changes.
- **Next H02-D2C2f.12l:** make recovery paths fence-aware, add durable state transitions and deterministic fault injection; add positive governed Quarkus/CDI test; review linearization and audit-failure handling before closure.

## H02-D2C2f.12l — Fence-aware recovery and replay (bounded safety slice; Fedora validation pending)

- **Baseline:** `main` SHA `bf6db542c49e70780fddc0841078a73feefaf266`, merge PR #127 (12k).
- **Production:** governed recovery from final file or `.staging`, `recoverWritten`, and completed-artifact `replay` now require a durable publication admission before materializing/reconciling evidence. Legacy non-governed flow remains unchanged. Missing admission fails closed and never triggers a second HTTP invocation.
- **Tests:** extend `GovernedToolResourcePolicyStoreTest` with admission absence before commitment, durability after restart, revocation-after-admission ordering, and unknown-attempt rejection. Existing governed E2E and full regression must run on Fedora.
- **Limits:** no cross-store atomic transaction, no automatic recovery of a model response lost before `FILE_WRITTEN`, no crash/COMMIT fault injection or positive CDI E2E. A valid admission is necessary but is not sufficient to guarantee that all cross-store evidence has been reconciled. No change to production default flags. H02-D2C2f remains open.
- **Next H02-D2C2f.12m:** deterministic restart/fault-injection around `FILE_WRITTEN`/rename/communication append; positive governed Quarkus CDI path and formal closure matrix. Evaluate legacy effects predating admission explicitly instead of silently grandfathering them.


## H02-D2C2f.12m — Deterministic publication interruption/restart recovery (bounded slice; Fedora validation pending)

- **Baseline:** `main` SHA `345cfedb28ecc4651cc35993e13a61c6270b7919`, PR #128 merged (12l).
- **Implementation:** instance-scoped, package-private no-op `PublicationCheckpoint` in `WorkspaceDocumentationService` at three boundaries: after durable `FILE_WRITTEN`, after `ATOMIC_MOVE`, and after idempotent communication append but before `ARTIFACT_RECORDED`. No external flag, mutable global state or new privileged API. Test simulates failure with an exception, creates a fresh service instance and reconciles the existing staging/final artifact without replaying HTTP.
- **Tests:** three governed SQLite+mock HTTP integration cases in `GovernedDocumentationConsumerE2ETest`: verify one HTTP call, `EVIDENCE_PENDING`, replay and no duplicate external model call after restart. Existing suite remains mandatory.
- **Explicit limitations:** deterministic exception + fresh Java service is **not** an OS process crash, power loss, fsync certification or SQLite COMMIT crash. Positive governed CDI `@QuarkusTest` remains pending. No cross-store exactly-once guarantee; no default feature flag changes. H02-D2C2f remains open.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`, `./gradlew clean test --no-daemon --console=plain`, `git diff --check`; inspect reports before PR.
- **Next H02-D2C2f.12n:** isolated positive governed CDI/Quarkus E2E, subprocess kill/restart and durable fault matrix; review final closure criteria before H02-D2C3 observability/shadow/rollback.

## H02-D2C2f.12n — Positive governed Quarkus/CDI Event Bus (bounded slice; Fedora validation pending)

- **Baseline:** `main` SHA `8975ed236cb5bdb4a4099886c3ea04906ea440a2`, merge PR #129 (12m).
- **Scope:** positive `@QuarkusTest` with production CDI-managed `WorkspaceDocumentationEventBus`, governed selection enabled only in isolated test profile, actual activated specialist registry, SQLite lease/grants/admission and loopback HTTP mock. Checks successful documentation, durable admission, idempotent replay and exactly one observed HTTP call for this fixture.
- **Isolation:** unique temporary workspace and databases; no Ollama dependency; no production flags or code changes. A bounded test profile deliberately starts the local HTTP server before Quarkus boots.
- **Limitations:** test profile and in-process HTTP mock do not establish OS-process kill/restart, SQLite power-loss safety, or distributed exactly-once semantics. CDI positive test is not a substitute for negative authorization tests. H02-D2C2f remains open pending Fedora verification and independent-process restart certification.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationQuarkusPositiveTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Inspect JUnit reports before PR.
- **Next H02-D2C2f.12o:** separate-JVM restart harness and durable fault matrix, review revocation/publication ordering and final governance guarantees before H02-D2C3 observability/shadow/rollback.

## H02-D2C2f.12o — Abrupt separate-JVM publication recovery (bounded slice; Fedora validation pending)

- **Baseline:** `main` SHA `cadfd6aa0a66be7c1efa5350e146e28716dfab26`, PR #130 merged (12n).
- **Implementation:** test-only `GovernedDocumentationProcessCrashChild` runs in a separate Java process, invokes the production governed documentation service and uses `Runtime.halt(73)` at the three existing publication checkpoints: `AFTER_FILE_JOURNAL`, `AFTER_ATOMIC_MOVE`, `AFTER_ARTIFACT_APPEND`. Parent JUnit process retains isolated loopback mock HTTP server and persistent SQLite/workspace; recovery is performed by a new service instance in the parent process. No production code, configuration or feature flag changes.
- **Acceptance:** child exits exactly 73; parent observes exactly one HTTP request, successful `EVIDENCE_PENDING` recovery, matching SHA-256 and stable artifact record ID on replay. Unexpected child completion, classpath failure and timeout fail the test rather than silently skipping it.
- **Scope limitations:** `Runtime.halt` is an abrupt JVM termination, not power-loss simulation or a guarantee of filesystem fsync/SQLite crash consistency; the parent JVM continues to run. SQLite and filesystem are not atomically committed together. The external HTTP request is observed exactly once in this fixture, not globally exactly-once. No automated recovery is claimed for an ambiguous reserved invocation before durable file journal. H02-D2C2f remains open.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Confirm child classpath and subprocess exit behavior on Fedora before PR.
- **Next H02-D2C2f.12p:** formal governance closure matrix and ADR covering durable admission/revocation ordering, crash boundaries, residual SQLite/fsync risks, legacy compatibility, negative CDI scenarios, and an explicit go/no-go decision for H02-D2C3 observability/shadow/rollback.


## H02-D2C2f.12p — Formal governance assurance review (NO-GO for closure; Fedora validation pending)

- **Baseline:** `main` SHA `6644338821a9571de2b90bfae0b37676cf0b95ce`, PR #131 merged (12o).
- **Artifacts:** `docs/adr/H02-D2C2f-12p-governance-certification.md` and `docs/governance/H02-D2C2f-assurance-matrix.md` enumerate tested invariants, failure semantics, evidence pointers, missing scenarios, residual risks and explicit release gates.
- **Static gate:** `python3 scripts/check-governance-12p.py` verifies required artifacts and production anchors. It is not a substitute for dynamic tests or a security audit.
- **Decision:** **GO** to merge the bounded audit after review and Fedora tests; **NO-GO** to close H02-D2C2f or enable additional privileged effects. Positive CDI, deterministic crash and separate-JVM tests are bounded evidence, not power-loss/fsync, cross-store atomicity or globally exactly-once proof.
- **No production changes:** no feature flags, runtime behavior or operator policy changes in this slice.
- **Validation:** `python3 scripts/check-governance-12p.py`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Attach test report and CI evidence before PR.
- **Next H02-D2C2f.12q:** implement negative CDI, concurrent recovery/revocation and SQLite COMMIT fault matrix; draft operator runbook; revisit the NO-GO decision with measured evidence before H02-D2C3.


## H02-D2C2f.12q — Two-connection admission/revocation ordering and recovery runbook (bounded slice; Fedora validation pending)

- **Baseline:** `main` SHA `a040667342f77938bd7f29352bec75164ce5311e`, PR #132 merged (12p).
- **Implementation:** `GovernedPublicationConcurrencyTest` covers two independent SQLite policy-store connections with revoke-first, admit-first and simultaneous race outcomes; verifies durable admission state and fail-closed denial. No production code or default flag changes.
- **Operational artifact:** `docs/runbooks/H02-D2C2f-governed-recovery.md` describes evidence-preserving triage, policy-admission verification, SHA-256 integrity, quarantine, and no automatic HTTP retry for ambiguous invocation.
- **Scope boundary:** Two connections in one JVM are **not** two independent processes. These tests do **not** certify recovery-worker concurrency, fault injection at SQLite COMMIT, negative CDI scenarios, or filesystem power-loss durability. Do not mark H02-D2C2f complete.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationConcurrencyTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`. Review JUnit XML and Fedora runtime before PR.
- **Decision:** **GO** for bounded test/runbook merge after passing Fedora checks; **NO-GO** for full governance closure. 12p assurance matrix and ADR remain authoritative pending further evidence.
- **Next H02-D2C2f.12r:** separate-JVM concurrent recovery coordination, negative governed CDI matrix, SQLite transaction/COMMIT failure injection, tampered-material cases, then re-evaluate formal closure and H02-D2C3 readiness.


## H02-D2C2f.12r — Negative material-integrity recovery matrix (bounded slice; Fedora validation pending)

- **Baseline:** `main` SHA `5bf175eb3d03071a3eae68bbce512a5f2b6262f1`, PR #133 merged (12q).
- **Implementation:** three negative tests in `GovernedDocumentationConsumerE2ETest`: tampered staging after `AFTER_FILE_JOURNAL`, tampered final material after `AFTER_ATOMIC_MOVE`, and tampered completed output on replay. Each must fail closed and not issue a second HTTP request.
- **Artifacts:** `docs/governance/H02-D2C2f-12r-scope.md` records tested and untested boundaries. Production source, feature flags and operator permissions are unchanged.
- **Limitations:** no independent-JVM concurrent recovery, negative CDI matrix, SQLite COMMIT fault injection or power-loss/fsync guarantee. H02-D2C2f remains **NO-GO** for final closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12s:** independent-JVM concurrent recovery coordination and deterministic SQLite transaction/COMMIT fault injection, then update certification evidence and review GO/NO-GO.


## H02-D2C2f.12s — Cross-JVM publication admission/revocation ordering (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `409cfdc5c6216af43f1f28c9ecb0082c2633c15c`, PR #134 merged (12r).
- **Implementation:** `GovernedPublicationProcessChild` and `GovernedPublicationProcessTest` exercise real independent JVM processes against one SQLite policy DB: revoke-first, admit-first, and simultaneous starts; assert persisted admission matches observed decision and later revocation does not erase prior admission.
- **Evidence:** `docs/governance/H02-D2C2f-12s-process-evidence.md` records scope and unresolved guarantees.
- **Limits:** This is policy ordering, **not concurrent document recovery**. No SQLite COMMIT fault injection, negative CDI matrix, cross-store atomicity or fsync/power-loss certification. No production changes. **NO-GO** for H02-D2C2f closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationProcessTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12t:** independent-JVM concurrent document recovery and deterministic SQLite COMMIT failure injection; update certification matrix and decide on production fencing fixes.


## H02-D2C2f.12t — Independent-JVM crash recovery and concurrent completed replay (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `2c96374e8e7f2027195c2add53ab12e86b5bb96b`, PR #135 merged (12s).
- **Implementation:** test-only `GovernedDocumentationRecoveryChild` and new `GovernedDocumentationConsumerE2ETest` scenarios: distinct child JVM recovers after each of `AFTER_FILE_JOURNAL`, `AFTER_ATOMIC_MOVE`, `AFTER_ARTIFACT_APPEND`; two JVMs concurrently replay an already completed attempt; HTTP count stays at one, durable artifact ID remains stable.
- **Evidence:** `docs/governance/H02-D2C2f-12t-recovery-evidence.md` documents tested boundaries and outstanding gaps. No production or default-flag changes.
- **Limits:** concurrent **completed replay** is not concurrent **unfinished recovery**. SQLite COMMIT fault injection, negative CDI, writer fencing and power-loss durability remain unproven. **NO-GO** for overall H02-D2C2f closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12u:** concurrent unfinished recovery across JVMs, deterministic COMMIT failure injection, production coordination/fencing if needed, negative CDI matrix and assurance review.


## H02-D2C2f.12u — Independent-JVM unfinished recovery contention (bounded diagnostic; Fedora validation pending)

- **Baseline:** `main` SHA `77fbb31ff936721222ef763b887f68fba8c1f600`, PR #136 merged (12t).
- **Implementation:** `GovernedDocumentationRecoveryRaceChild` and `twoJvmCompeteToRecoverUnfinishedPublication` launch two real JVM contenders behind a shared file gate after each abrupt-crash checkpoint. Require at least one successful recovery, valid final replay/hash and no extra model HTTP.
- **Evidence:** `docs/governance/H02-D2C2f-12u-race-scope.md` documents tested and untested guarantees. Production remains unchanged.
- **Limitations:** gate release does not guarantee simultaneous file operations; losing contender may fail. No deterministic COMMIT fault injection, negative CDI matrix or power-loss certification. **NO-GO** for H02-D2C2f closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12v:** diagnose any race failures, add production coordination/fencing if required, deterministic SQLite COMMIT fault injection, negative CDI and update assurance/ADR before closure review.


## H02-D2C2f.12v — SQLite admission pre-COMMIT failure and lost-ack reconciliation (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `2457c327490e2803489646e653741b6197eb3436`, PR #137 merged (12u).
- **Implementation:** new `GovernedPublicationTransactionFaultTest`: SQLite trigger injects an ABORT after admission ALLOW audit but before INSERT/COMMIT, verifying full rollback and clean retry after trigger removal; simulated lost acknowledgment after successful COMMIT requires durable read and rejects blind duplicate admission.
- **Evidence:** `docs/governance/H02-D2C2f-12v-sqlite-fault-evidence.md`. No production source or feature flag changes.
- **Limitations:** no exception injected **inside** JDBC COMMIT, no crash/fsync or power-loss guarantee, no negative CDI matrix or production fencing changes. **NO-GO** for H02-D2C2f closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationTransactionFaultTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12w:** negative CDI integration matrix, fault injection at actual COMMIT boundary, assess writer fencing, update assurance matrix and ADR; decide readiness for final governance closure.

## H02-D2C2f.12w — Negative Quarkus CDI Event Bus boundary (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `a5383eac13b7c3629447ca8170ab610364e50e1d`, PR #138 merged (12v).
- **Implementation:** negative real CDI Event Bus matrix added to `GovernedDocumentationQuarkusPositiveTest`: traversal path, absolute path, forged attempt, wrong SHA-256 and missing workflow ID must fail before HTTP. No production or default-flag changes.
- **Evidence:** `docs/governance/H02-D2C2f-assurance-matrix.md` extended with explicit 12w coverage and remaining gaps.
- **Limits:** not actual JDBC COMMIT exception injection; revocation/tampering CDI cases remain incomplete; no production fencing or power-loss certification. **NO-GO** for H02-D2C2f closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationQuarkusPositiveTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12x:** actual COMMIT-boundary fault seam with read-after-unknown reconciliation, CDI revocation and evidence-tamper negatives, explicit production fencing decision, certification matrix/ADR review.

## H02-D2C2f.12x — COMMIT acknowledgement ambiguity (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `2f5af86fca2f8b7372f7567aaf767f3ff482ce37`, PR #139 merged.
- **Implementation:** package-private post-COMMIT observer in `GovernedToolResourcePolicyStore`; `GovernedPublicationCommitBoundaryTest` checks durable reconciliation and duplicate denial.
- **Evidence:** `docs/governance/H02-D2C2f-12x-commit-evidence.md` and assurance matrix.
- **Limits:** no inside-COMMIT fault, power-loss or cross-store atomicity proof; negative CDI revocation/tamper and fencing remain incomplete. **NO-GO** overall.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationCommitBoundaryTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12y:** CDI revocation/tamper with isolated fixtures, fencing decision, runbook and evidence-based GO/NO-GO.

## H02-D2C2f.12y — isolated CDI tamper/revocation gate (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `7fe8968c0aecd0d4e8c8371a22947d42c48614fc` (PR #140 merged).
- **Implementation:** independent Quarkus test profile cloned from established positive fixture; source tamper must fail before HTTP, then original restored and policy revoked durably; CDI request must fail with zero HTTP and zero publication admission.
- **Evidence:** `docs/governance/H02-D2C2f-12y-cdi-evidence.md` and assurance matrix. No production code change.
- **Limits:** source tamper only (not journal/artifact tamper), revocation before model invocation only, no new fencing guarantee, no power-loss certification. **NO-GO** overall.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationQuarkusRevocationTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.12z:** journal/artifact tamper and cross-JVM fencing review, recovery runbook, formal assurance GO/NO-GO decision with explicit residual risks.

## H02-D2C2f.12z — replay integrity negatives (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `f4238a9db5394cad10f5c514f67d2bac1038547a` (PR #141 merged).
- **Implementation:** extend real CDI positive/replay test with post-publication file tamper and durable journal SHA tamper; each must reject replay without new model HTTP; restore evidence after each case. No production changes.
- **Evidence:** `docs/governance/H02-D2C2f-12z-integrity-evidence.md` and assurance matrix.
- **Limitations:** does not cover coordinated evidence replacement, malicious DB writer, full cross-JVM fencing, power loss or cross-store atomicity. **NO-GO** for H02-D2C2f closure.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationQuarkusPositiveTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.13a:** adversarial cross-process fencing and coordinated evidence tamper threat model, production runbook, ADR and bounded certification verdict.


## H02-D2C2f.13a — bounded SQLite contention and recovery runbook (Fedora validation pending)

- **Baseline:** `main` SHA `512aa11f5d2e4b3c164d22a845d11536cfd99685` (PR #142 merged).
- **Implementation:** `GovernedPublicationConcurrentAdmissionTest` races two independent SQLite connections on the same admission; assert one successful admission and one durable ALLOW audit. No production changes.
- **Evidence and runbook:** `docs/governance/H02-D2C2f-13a-concurrency-evidence.md` documents the stale-owner threat model, non-leased invocation reservation and manual fail-closed recovery.
- **Limits:** this is **not** a two-JVM fencing test; filesystem stale-writer exclusion, power-loss durability and cross-store atomicity remain unproven. Overall **NO-GO**.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationConcurrentAdmissionTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.13b:** real two-JVM stale-owner/resume scenario, fencing ADR and enforcement or explicit operational restriction; formal evidence-based certification review.

## H02-D2C2f.13b — stale JVM admission boundary (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `6e55112cd56af17b64776f1e423b8239deb37801` (PR #143 merged).
- **Implementation:** extend real two-JVM policy tests with explicit ready/release process barriers. An already-running JVM attempts publication admission after a second JVM durably revokes the policy or admits the attempt; stale request is denied and durable audit/admission counts remain correct. No production changes.
- **Evidence:** `docs/governance/H02-D2C2f-13b-stale-jvm-evidence.md` and assurance matrix.
- **Boundary:** tests **admission fencing only**, not a paused writer between admission and filesystem commit, ownership epochs, hostile database modifications, power loss or cross-store atomicity. Global **NO-GO**.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationProcessTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.13c:** investigate post-admission stale filesystem writer, enforce a scoped invariant or formalize single-writer operational contract, and review certification evidence.

## H02-D2C2f.13c — post-admission stale-owner boundary (bounded; Fedora validation pending)

- **Baseline:** `main` SHA `3985ea3e65dc04b86594fb26a7fdf3b7798644cf` (PR #144 merged).
- **Implementation:** deterministic two-JVM barrier **after** durable admission. A separate JVM revokes while the first is paused; the old JVM observes the historical admission but cannot obtain another admission. Audit ALLOW count remains one. No filesystem write and no production changes.
- **Evidence:** `docs/governance/H02-D2C2f-13c-post-admission-evidence.md`.
- **Security conclusion:** a committed admission survives revocation by design and **is not** a fencing token. The test deliberately does not claim stale filesystem writer exclusion. Keep ambiguous attempts quarantined and do not reassign automatically. **NO-GO** global.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationProcessTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.13d:** test the actual `WorkspaceDocumentationService` post-admission write window using controlled process barriers; decide on filesystem fencing or a reviewed single-writer deployment contract before certification.


## H02-D2C2f.13d — publication rechecks (Fedora validation pending)

- **Baseline:** `main` SHA `048b7cabcd70ba26178c51a4099d8a793f17d8b2` (PR #145 merged).
- **Production:** after durable admission, revalidate governed authorization before creating output; after FILE_WRITTEN journal and immediately before atomic move, revalidate again; on staging recovery, revalidate before atomic move. Legacy ungoverned behavior remains unchanged.
- **Evidence:** `docs/governance/H02-D2C2f-13d-publication-recheck-evidence.md`.
- **Safety boundary:** bounded revocation checks, NOT atomic fencing: revocation may commit after the last check and before the filesystem move. Existing admitted attempts retain historical admission. Recovery with revocation now fails closed before staging materialization. No automatic retries of ambiguous model calls. **NO-GO** globally.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentation*' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Next H02-D2C2f.13e:** deterministic real CDI post-admission revocation and staging recovery negative tests; evaluate OS-level single-writer exclusion or enforceable fencing for atomicity at publication boundary.


## H02-D2C2f.13e — deterministic post-admission revocation test (Fedora validation pending)

- **Baseline:** `main` SHA `e55917e6c070e4c2e2b765e82059ba3cf6742b67` (PR #146 merged).
- **Test:** isolated governed CDI fixture, real local HTTP model response, durable SQLite admission, synchronous revocation at `AFTER_PUBLICATION_ADMISSION`, and negative assertions for document/staging materialization and repeat model calls.
- **Guarantee:** revocation *at the injected checkpoint* is denied by the production recheck before writing. This does not prove atomic fencing or cover revocation after `AFTER_FILE_JOURNAL`.
- **Validation pending:** `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationPostAdmissionTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Global:** NO-GO. **Next H02-D2C2f.13f:** deterministic post-journal revocation and staging recovery tests, then enforceable single-writer/fencing design and two-JVM verification.


## H02-D2C2f.14 — cooperative cross-JVM publication/revocation gate (Fedora validation pending)

- **Baseline:** `main` SHA `32a07ed7b722841d3969f2e67c973c31facd936b` (PR #147 merged).
- **Production:** host-local OS `FileLock` + JVM mutex shared by governed document publication and operator `revoke`. After admission, publication acquires the gate, rechecks current authorization under the gate, and holds it through `ATOMIC_MOVE`. Staging recovery takes the same gate and rechecks before materialization. Revocation acquires the gate before its SQLite transaction and releases only after durable commit.
- **Ordering contract:** if revocation commits first, subsequent publication denies; if publication holds the gate first, revocation acknowledgement waits until after the move. A historical admission alone is never sufficient. The legacy path remains ungated.
- **Deployment constraints:** single host, local filesystem with reliable advisory locks, all policy revocation and governed document publication must use this implementation and the same canonical policy DB path. No bypass writers, network filesystem, hostile filesystem mutation, cross-store atomicity, power-loss durability, or distributed fencing claim. Lock files must not be removed while processes run.
- **Tests:** `GovernedPublicationGateTest` tests intra-JVM serialization; existing post-admission revocation test remains valid because its checkpoint occurs before gate acquisition. Two-JVM process interruption and operational verification are required before a global GO.
- **Evidence:** `docs/governance/H02-D2C2f-14-publication-fencing-evidence.md`. **Global NO-GO pending Fedora regression and cross-JVM acceptance evidence.**
- **Next:** do not add another recheck-only milestone. Run cross-JVM ordering/crash acceptance, decide whether to formally restrict deployment to one host/local filesystem, and close H02-D2C2f only against an explicit acceptance checklist.


## H02-D2C2f.15 — two-JVM publication gate acceptance (Fedora validation pending)

- **Baseline:** `main` SHA `8aa3c2d118618b545643e6cea83eba4c9aaab10e` (H02-D2C2f.14 merged).
- **Tests:** real independent JVMs prove OS lock exclusion and kernel lock release after forced process termination; explicit stdout/stdin barriers, no test-only production hooks.
- **Scope:** additive tests and evidence only; no production contract changes. Existing governed revocation, journal, replay and in-process tests remain.
- **Evidence:** `docs/governance/H02-D2C2f-15-cross-jvm-acceptance.md`.
- **Validation:** `./gradlew :apps:control-plane:test --tests '*GovernedPublicationGateProcessTest' --no-daemon --console=plain --rerun-tasks`; `./gradlew clean test --no-daemon --console=plain`; `git diff --check`.
- **Global:** **NO-GO** pending full two-JVM revocation-versus-document-move and interrupted staging recovery assertions against durable policy/journal/artifact stores. Do not claim end-to-end fencing from gate-only tests.
- **Next:** implement end-to-end cross-process governed publisher/operator harness and durable-effect verification as a single acceptance increment, then make an explicit GO/NO-GO decision; do not add more authorization checkpoints.


## H02-D2C2f.16 — real cross-JVM revocation integration (Fedora validation pending)

- **Baseline:** `main` `b75567150278df4b29264187db9b3ee7063be54b` (H02-D2C2f.15 merged).
- **Test:** `GovernedRevocationCrossJvmTest` holds the actual production publication gate in a separate JVM and invokes the actual SQLite-backed operator revocation in the test JVM. Asserts no early acknowledgement, active policy while held, and durable REVOKED after release.
- **Limit:** process timing is bounded but revocation-at-lock barrier is not instrumented. No full documentation publication, `.staging` crash recovery, or exactly-once model guarantee proven here.
- **Evidence:** `docs/governance/H02-D2C2f-16-revocation-process-evidence.md`.
- **Validation pending:** focal test and `./gradlew clean test` on Fedora. **Global NO-GO.**
- **Next:** deterministic full governed CDI publication/revocation and crash recovery, without proliferating isolated recheck milestones; close only on verified acceptance evidence.


## H02-D2C2f.17 — publication lock-path hardening (Fedora validation pending)

- **Baseline:** main `2a938512de4306a338a7997478ea8312addca04f` (H02-D2C2f.16 merged).
- **Production:** refuse symlink/non-regular publication lock paths, use `NOFOLLOW_LINKS` when opening lock channel, reject symlinked immediate parent; protect cooperative OS-lock coordination against accidental lock-file redirection.
- **Tests:** `GovernedPublicationGateSafetyTest` for symlink target preservation, directory substitution and recovery after invalid path removal; rerun cross-JVM gate/revocation and full suite.
- **Limits:** no malicious parent-directory replacement protection, no hardlink defense, no distributed fencing, no cross-store transaction or power-loss durability.
- **Global status:** **NO-GO**. This is a bounded hardening slice, **not** the full end-to-end acceptance originally proposed for .17. See `docs/governance/H02-D2C2f-17-lock-path-hardening.md`.
- **Next:** actual governed documentation publish/recover/revoke race acceptance across JVMs, with staging tamper/crash and journal/communication/HTTP assertions; decide GO/NO-GO only on recorded evidence.


## H02-D2C2f.18 — independent-JVM staging recovery negatives (Fedora validation pending)

- **Baseline:** main `60e1494a695a7bc6376f494d066a9d137019a9ff` (.17 merged).
- **Tests:** abrupt publisher JVM termination at `AFTER_FILE_JOURNAL`; separate recovery JVM must fail closed for (a) durably revoked policy and (b) tampered staged bytes, with no final document and no second HTTP invocation. Reuses existing real SQLite, activated specialist, filesystem and HTTP fixtures.
- **Evidence:** `docs/governance/H02-D2C2f-18-cross-jvm-recovery-negatives.md`. No production code change.
- **Validation:** targeted `GovernedDocumentationConsumerE2ETest`, full `clean test`, `git diff --check` on Fedora. Do not claim results before execution.
- **Global:** **NO-GO**. These are negative recovery tests, not full publication/revocation race acceptance or power-loss durability.
- **Next:** implement deterministic real-service cross-JVM publish-first/revoke-first ordering and durable artifact/audit assertions, consolidate evidence into formal bounded GO/NO-GO; avoid further gate-only increments.


## H02-D2C2f.19 — real-service cross-JVM publication/revocation ordering (Fedora validation pending)

- **Baseline:** `main` `589eedb01cf395da9c704d43691e8fc08606c114` (.18 merged).
- **Tests:** independent publisher JVM pauses inside the production gate after `FILE_WRITTEN`; operator revocation cannot acknowledge until publisher completes; verify durable `ARTIFACT_RECORDED`, final file digest, one model HTTP invocation, revoked policy, and fail-closed replay. Complementary revoke-first test starts a separate publisher after durable revoke and requires no HTTP, file, or journal effect.
- **Evidence:** `docs/governance/H02-D2C2f-19-publication-revocation-acceptance.md`. No production changes.
- **Limits:** bounded contention timing rather than explicit revoker-at-lock barrier; no cross-store atomicity, power-loss durability, distributed fencing, hostile filesystem protection or globally exactly-once HTTP.
- **Validation:** targeted `GovernedDocumentationConsumerE2ETest`, full `clean test`, `git diff --check`, Fedora test reports and reviewer approval required.
- **Decision:** **NO-GO pending observed Fedora tests and formal assurance sign-off**. Next: consolidate assurance matrix with explicit scope, runbook and evidence-backed conditional GO/NO-GO. Avoid further isolated gate-only milestones; resume multiagent engineering workflow after governance acceptance.
