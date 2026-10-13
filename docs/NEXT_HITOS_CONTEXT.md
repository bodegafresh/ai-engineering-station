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
