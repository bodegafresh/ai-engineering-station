# F3.5.3 — Verified Completion Boundary

Base: `main` commit `968a46a302a82db3faa7a6f153f9bb80c16016a4` (PR #48).

## Cambio
- Se deshabilita `approveEvidence(...)` (antes admitía IDs sin verificar); falla explícitamente.
- `approveVerifiedEvidence(...)` comprueba evidencia canónica, hash del registro, pertenencia de workflow/tarea, auditoría APPPROVED sobre subjectId exacto, material SHA-256 si existe, y actualiza con compare-and-set (`EVIDENCE_PENDING` y `evidence_ref`).
- `ExecutionQualityGate` mantiene el contrato Event Bus y ejecuta la transición a través de la nueva frontera.
- Se actualiza la prueba heredada y se agrega `ExecutionVerifiedCompletionTest` para demostrar que el bypass queda cerrado, rechazo, auditoría cruzada, replay y desbloqueo de dependencias.

## Limitaciones conocidas
- Las dos bases SQLite (comunicación y ejecución) no participan de una única transacción ACID; la auditoría es inmutable, pero el filesystem puede variar después de comprobar el hash. No afirmar atomicidad global.
- La validación material debe usar la raíz del workspace configurada. No se aceptan documentos sin verificación si contienen referencias `workspace-doc-sha256:`.
- No se cambian estados anteriores ni se aprueban tareas automáticamente.
- No se han ejecutado pruebas Gradle en este paquete; validarlas localmente antes de abrir PR.

## Siguiente plan: F4 E2E
1. **F4.1**: Harness determinista con servidor Ollama HTTP simulado, Event Bus real y SQLite temporal; importar DAG de dos tareas, autorizar, ejecutar, verificar auditoría y manifest final. Inyectar rechazo, replay, crash/restart y evidencia manipulada.
2. **F4.2**: Prueba opt-in con Ollama local real, workspace aislado y permisos mínimos; medir latencia, tokens, número de invocaciones y calidad de artefactos.
3. **F4.3**: Smoke CLI y reporte de aceptación, indicadores de éxito, dead letters, lease expirado, recuperación, idempotencia, observabilidad y límites de recursos.
4. **F5**: capacidades adicionales por especialista, autorización explícita y herramientas controladas; no ejecutar comandos arbitrarios sin permisos.

## Validación
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionHandoffStoreTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## Riesgos para siguiente revisión
- Extender `ExecutionVerifiedCompletionTest` con casos de material manipulado, concurrencia entre conexiones y reinicio; criterio para F4.
- Revisar que el gate no dé `COMPLETED` como replay sin volver a verificar material cuando se consulta después de la entrega.
