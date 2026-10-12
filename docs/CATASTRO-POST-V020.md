# AIES — Catastro técnico posterior a v0.20

## Existente (evidencia: main inspeccionado y smoke comunicado por usuario)
- Quarkus control plane, Gradle multi-módulo, Java 21, SQLite adapter.
- Routing de modelos Ollama local; v0.18 ejecuta design -> implementation -> review con DAG y modelos especializados.
- v0.17 memoria SQLite FTS5 con API opt-in; recuperación opcional desde evaluación.
- v0.19 lectura bounded de repositorio, opt-in, por projectId; smoke COMPLETED con App.java.
- CapabilityPolicy/ToolGateway existen; la ejecución de lectura usa gateway.
- v0.20 introduce endpoint separado de propuestas de reemplazo de un archivo con hashes y aprobación manual; no hay integración automática desde agentes.

## P0 — Bloqueadores antes de repositorios reales o escritura autónoma
1. Aislamiento de filesystem: worktrees por tarea, usuario sin privilegios, contenedores/namespace y protección TOCTOU con operaciones de directorio seguras.
2. Flujo humano real: diff visible, revisión explícita fuera de la API del modelo, separación de credenciales/roles y tokens de aprobación de un solo uso.
3. Persistencia y recuperación de propuestas/aprobaciones/auditoría; trazabilidad a tarea, usuario, modelo y commit base.
4. Aplicación de diffs multiarchivo con validación de rutas, permisos, límites, conflictos y rollback; no reemplazos arbitrarios.
5. Pruebas reales de proyecto en sandbox con límites de CPU/RAM/tiempo, bloqueo de red y control de comandos.
6. Pruebas de seguridad: symlink races, traversal, prompt injection, secretos, concurrencia, replays, SSRF y acceso por proyecto.

## P1 — Calidad y observabilidad
7. Conectar resultados de agentes a propuestas estructuradas de patch, con parser/validator determinista y revisión humana.
8. Registrar causas originales de ExecutionException, latencia por etapa, uso de tokens, errores Ollama, métricas OpenTelemetry, logs correlacionados.
9. Asegurar evaluación reproducible con hashes de contexto, snapshots y modelos/versiones/temperatura.
10. QA independiente: compilación, tests, linters, coverage, criterios de aceptación y evidence bundles; metadata del modelo no es evidencia de calidad.
11. Tests de integración HTTP para v0.20, reinicio, expiración, colisiones, seguridad y fallos de disco.
12. CI con checks de build, análisis estático, SCA, dependabot, SBOM y pruebas E2E.

## P2 — Producto y experiencia
13. UI para seleccionar proyecto, objetivos, vista de archivos/diff, aprobar/rechazar, progreso DAG y resultados.
14. Scheduler persistente para tareas largas, cancelación, reintentos idempotentes y cuotas por proyecto.
15. Optimización de prompts, context packing por relevancia, caché, perfiles de especialistas y benchmark de modelos.
16. Gestión de múltiples repositorios, configuración versionada y permisos por usuario/proyecto.
17. Documentación de despliegue Fedora, backups SQLite, restauración, upgrades y compatibilidad.

## Criterios de salida v0.20
- Gradle `clean test` pasa en el repositorio real.
- Sin token o flag deshabilitado: 404.
- Preparar no modifica archivos; aplicar sin aprobación falla.
- Hash obsoleto impide aplicar; aprobación correcta permite aplicar una sola vez.
- Validar la prueba con proyecto sintético, nunca con repositorio de trabajo valioso.
