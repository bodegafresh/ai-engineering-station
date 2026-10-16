# AIES — Inventario, integridad y normalización SQLite (H02-D2C2d)

**Estado:** implementado inventario de solo lectura, copia consistente y cuarentena opt-in restringida. **No** se han inspeccionado ni movido las bases reales del operador. La captura de DBeaver muestra nombres/tablas, no prueba de actividad, integridad, duplicación ni elegibilidad para eliminación.

## Inventario de propietarios (inspección de `main`)

| Archivo | Consumidor comprobado en código | Raíz actual / expectativa | Decisión |
|---|---|---|---|
| `tasks.db` | `TaskRuntime`, `SqliteTaskRepository` | `aies.data.dir` (default `./data`) | Conservar; tareas del motor base |
| `communication.sqlite` | `CommunicationStore`, `CommunicationEventBus` | `aies.agent-communications.data-root` (default `./data/agent-communications`) | Conservar |
| `planning.sqlite` | `PlanningStore`, `PlanningEventBus`, revisiones | misma raíz de comunicaciones | Conservar; plan y outbox |
| `science.sqlite` | `ScientificWorkflowStore`, `ResearchEvidenceStore` | misma raíz de comunicaciones | Conservar; workflow y evidencia científica |
| `execution-handoff.sqlite` | `ExecutionHandoffStore`, worker y calidad | misma raíz de comunicaciones | Conservar; leases, efectos, invocaciones, grants, ahora identidad por intento |
| `project-memory-v017.sqlite` | `SqliteProjectMemory`, `MemoryResource`, equipos | `aies.workspace-changes.sessions-root` / directorio de sesiones del consumidor | Conservar; FTS no es redundancia |
| `verification-evidence.sqlite` | `VerificationEvidenceRepository` | raíz de sesiones de workspace | Conservar |
| `workspace-changes.sqlite` | `SqliteWorkspaceChangeRepository` | raíz de sesiones de workspace | Conservar |
| `specialists.sqlite` | `SpecialistRegistryStore`, `GovernedSpecialistSelector` | `aies.specialists.governed-selection.database` (default `./data/specialists.sqlite`) | Conservar aunque v2 esté desactivado |
| `audit.sqlite` | `scripts/aies_p0.py` | raíz propia del script | Revisar uso operativo; no eliminar automáticamente |

**Observación:** varias tablas `*_outbox` son mecanismos durables de entrega/reintento, no duplicados que deban borrarse. Las tablas FTS de `project-memory-v017.sqlite` son internas de búsqueda y no deben manipularse individualmente.

## Normalización de ubicación: diseño y secuencia segura

- **Destino lógico futuro:** un único root configurable `AIES_DATA_ROOT`, con subdirectorios `sqlite/core`, `sqlite/workflows`, `sqlite/sessions`, `sqlite/governance` y `sqlite/legacy`, preservando nombres y límites de propiedad de cada DB. Es un **objetivo de migración**, no una ruta que ya usen todos los consumidores.
- **En este incremento:** mantener rutas de ejecución y defaults existentes. Cambiarlas ahora sin inspeccionar los datos del host provocaría nuevas bases vacías, aparentando pérdida de datos. La normalización efectiva requiere migración offline y cutover atómico con rollback y pruebas de reinicio; **no** introducir symlinks como puente SQLite con WAL.
- **Paso 1:** detener escritores, registrar variables de entorno efectivas y ejecutar `python3 scripts/aies-sqlite-inventory.py --root . > /tmp/aies-sqlite-inventory.json`.
- **Paso 2:** revisar `status`, `tables`, `foreign_key_violations`, ruta y propietario; cualquier `invalid_header`, `read_error`, `review_required` o `symlink_manual_review` bloquea migración y eliminación.
- **Paso 3:** respaldar con SQLite online backup a directorio externo nuevo: `python3 scripts/aies-sqlite-inventory.py --root . --backup-dir "$HOME/aies-db-backup-$(date +%Y%m%d-%H%M%S)" > /tmp/aies-sqlite-backed-up.json`. El backup nunca sobreescribe y verifica `quick_check`.
- **Paso 4:** revisar archivos desconocidos contra consumidores, configuración y sesiones. Solo bases desconocidas **vacías**, sin sidecars y sin handles verificables pueden pasar a cuarentena opcional, jamás borrado automático. Ejecutar únicamente con escritores detenidos y respaldo externo: `--quarantine-empty-unknown --confirm-stopped --backup-dir ...`.
- **Paso 5 (futuro hito):** plan de cutover con manifest `old_path → new_path`, verificación de tablas/filas/integridad, migración offline, cambio coordinado de configuración, arranque de prueba y rollback documentado. Sin evidencia del host, no hay permiso para mover o borrar archivos.

## Límites de la herramienta

1. No elimina, renombra, consolida ni ejecuta `VACUUM` sobre bases referenciadas por código.
2. No considera un archivo 'no usado' solo porque tenga cero filas: un esquema vacío puede ser requerido para el próximo trabajo.
3. No lee el contenido de filas ni expone secretos; devuelve nombres de tablas, recuentos, checks y tamaños.
4. El inventario por nombre de archivo es indicio de propietario, **no** prueba de acceso activo; hay rutas configurables y raíces externas no cubiertas.
5. `--quarantine-empty-unknown` requiere confirmación explícita de escritores detenidos, backup fuera del repositorio, y ausencia de sidecars/handles; cualquier incertidumbre bloquea.
6. Bases existentes fuera de `data/` y `apps/control-plane/data/` requieren un inventario adicional antes de migración.

## Relación con DAG y Context Broker

La identidad gobernada durable de cada intento es una unidad reutilizable por nodo DAG, con lease, versión y evidencia; el planificador y SQLite existentes siguen siendo fuente de verdad. Los futuros conectores de contexto no obtienen permisos por una respuesta de modelo. El hito no implementa DAG dinámico ni migración de almacenamiento.
