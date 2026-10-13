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
