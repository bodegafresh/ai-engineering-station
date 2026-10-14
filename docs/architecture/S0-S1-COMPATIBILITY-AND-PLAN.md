# S0/S1 — Compatibilidad, plan de implementación y criterios de gate

## Estado
S0 documentación y fixtures entregados como propuesta; S1 **NO IMPLEMENTADO**; F4 **ABIERTO**. Esta entrega no altera `SpecialistEventBus`, `SpecialistCatalog`, `PlanningEventBus`, configuración, APIs ni datos.

## Contrato legado confirmado en main
Event Bus `aies.specialists.select.v1`: request `{capabilities: string[], permittedTools: string[], budget: int}`; response seleccionada `{selected:true,specialistId,costUnits}` o `{selected:false}`. El selector exige `capabilities.containsAll(requiredCapabilities)`, `permittedTools.containsAll(specialist.tools)` y `costUnits <= budget`, ordenando por costo y luego ID. No hay `documentation` en el catálogo legado. `PlanningPolicy` devuelve `NEEDS_CLARIFICATION` antes de intentar asignación si hay hallazgos, incluyendo crítico con `INSPECTION`; si no encuentra especialista, `BLOCKED_SPECIALIST`. No modificar semánticas por S0.

## Compatibilidad
- No renombrar capacidades antiguas ni asignar alias jerárquicos implícitos.
- No crear consumidor `select.v2` hasta S1; esquema v2 es contrato **propuesto**.
- No prometer que `maxConcurrent` se aplica efectivamente: v1 lo almacena como metadato.
- Cuando se active S1, migrar las cuatro semillas exactas; fixtures y selección v1 deben coincidir.
- Si flag S1 está activo y SQLite falla, rechazar sin fallback al catálogo fijo.
- Ningún perfil propuesto puede publicarse sin aprobación y hash exacto.

## S1 por incrementos
1. Decisiones pendientes S0: canonicalización formal, scopes operador, semántica revocación, revisión/cache, retención.
2. Implementar contratos Java y migraciones SQLite aisladas, `registry.enabled=false`.
3. Crear validación de referencias, estado, hash, aprobación autorizada y CAS; sin rutas productivas nuevas hasta tener pruebas.
4. Adaptador v1 compatible y selección v2 opt-in; pruebas equivalencia y falla cerrada.
5. Endpoints autenticados de operador y auditoría durable; negativos, rollback y reinicio.
6. Solo con evidencias reales, evaluar activación controlada. No declarar S1 cerrado por pasar tests S0.

## Amenazas y gates
Prompt injection, actor falso, hash cambiado, permisos implícitos, aliases ampliadores, replay, carrera CAS, revocación concurrente, saturación, secretos en logs y corrupción SQLite. Gates: contrato válido, referencias válidas, actor autenticado, autorización específica, hash estable, estado permitido, transacción durable, auditoría y rechazo de efectos no autorizados.

## Verificación local de S0
`bash scripts/s0-specialists-contract-tests.sh` requiere `jq` y valida JSON, fixture legado y negativas estructurales. No equivale a un validador completo JSON Schema ni a JUnit/Quarkus E2E. Validar además `git diff --check`; `./gradlew clean test --no-daemon --console=plain` cuando exista checkout Java disponible.
