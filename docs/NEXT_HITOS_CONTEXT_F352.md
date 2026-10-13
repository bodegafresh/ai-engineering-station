# AIES — F3.5.2: Concurrency & Recoverable Publication

Base: `main` commit `a855115641eae91942ef4bf55920e0b7c9086257`.

## Implementado
- Test con dos conexiones SQLite y dos hilos compitiendo por la misma reserva; comprueba persistencia tras reinicio.
- Estado de diagnóstico de invocaciones: `NOT_PREPARED`, `PREPARED_NOT_INVOKED`, `AMBIGUOUS_INVOCATION`, `FILE_RECOVERY_REQUIRED`, `RECORDED`.
- Documento generado en staging determinista; `FILE_WRITTEN` persiste hash y destino antes del `ATOMIC_MOVE`.
- Recuperación de staging solamente si el journal contiene la fase correcta, ruta esperada y SHA-256 exacto; no se vuelve a invocar Ollama.
- Sin cambios de REST, contratos del Event Bus ni habilitación automática de herramientas.

## Límites explícitos
- No hay fsync del archivo/directorio ni transacción ACID entre SQLite y filesystem. Una caída eléctrica puede requerir intervención.
- Una caída antes de registrar FILE_WRITTEN deja staging sin journal: se rechaza; no se considera evidencia.
- Una caída con invocación reservada y sin salida sigue siendo ambigua: no se repite el modelo.
- La prueba concurrente verifica la reserva entre conexiones, no cuenta invocaciones HTTP simultáneas a Ollama.
- **No se ha cerrado la frontera de auditoría**: `approveEvidence` debe migrar a una API interna con verificación completa antes de F4.

## Próximo incremento: F3.5.3 — Verified Completion Boundary
1. Revisar `ExecutionQualityGate` y `ExecutionHandoffStore.approveEvidence` para impedir aprobación directa.
2. Vincular el registro AUDIT APPROVED al subjectId y al SHA-256 del artefacto.
3. Mantener `EVIDENCE_PENDING` hasta confirmación válida; tests de manipulación, auditoría falsa, carrera y fencing.
4. Pruebas E2E con dos tareas y Ollama simulado; luego Ollama local real.

## Validación local obligatoria
```bash
./gradlew :apps:control-plane:test --tests '*ExecutionSingleFlightConcurrencyTest' --tests '*WorkspaceDocumentationServiceTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```
