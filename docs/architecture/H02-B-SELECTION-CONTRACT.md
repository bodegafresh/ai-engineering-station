# H02-B — Contrato común de selección (fase aislada)

Estado: preparado para validación Fedora; **no conectado a producción**.

## Evidencia H02-A

El inventario `aies-inventory.json` recibido reporta referencias estáticas entre `SpecialistEventBus` y `PlanningEventBus`/`SpecialistResource`; entre `TeamPlanner` y `TeamPlanningResource`; entre `CapabilityRouter` y `SpecialistPromptComposer`/`RoutingPreviewResource`; y entre `SpecialistRegistryStore` y los servicios de gobernanza. El propio inventario advierte que no acredita cableado runtime. El estado de flags en ejecución no puede deducirse de defaults.

## Implementación

`SpecialistSelectionContract` es un límite Java puro con `Request`, `Candidate`, `Decision`, `Outcome` y `Source`. La función `select` es determinista y no tiene efectos laterales. `legacyV1()` refleja exclusivamente los cuatro especialistas congelados del Event Bus v1. Una suite compara resultados contra `SpecialistCatalog.select` para capacidades y presupuestos representativos.

- No se modifica `SpecialistEventBus`, `SpecialistResource`, `TeamPlanner`, `CapabilityRouter` ni el registro SQLite.
- `GOVERNED_V2` es una etiqueta de procedencia, **no una prueba de aprobación**. El llamador deberá filtrar `ACTIVE` con verificación de integridad, revisión y autorización antes de entregar candidatos.
- `permittedTools` es una restricción de elegibilidad, **no una concesión de acceso**. Los permisos de herramientas se verifican nuevamente en el punto de ejecución.
- `NO_MATCH` es resultado de selección sin candidato. `BLOCKED_SPECIALIST` y `NEEDS_CLARIFICATION` quedan modelados para futuros adaptadores; no se inventan razones sin evidencia.
- No se incluye en el contrato capacidad de documentación en el catálogo legacy.

## Próximo H02-C

Integrar adaptador gobernado read-only que solo considere revisiones `ACTIVE` con integridad verificable, y flag independiente deshabilitado por defecto. Definir política de versiones, colisiones de ID, estado de revocación, fail-closed ante SQLite y trazabilidad; probar compatibilidad y rollback antes de tocar Event Bus v1.

## Validación

```bash
./gradlew :apps:control-plane:test --tests '*SpecialistSelectionContractTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

F4/G10-K sigue OPEN. Ningún E2E de Quarkus/Ollama se certifica aquí.
