# Catastro AI Engineering Station — v0.6

## Base verificada (GitHub, rama feat/tool-gateway-v05)

- Monorepo Gradle: `modules/domain`, `modules/orchestration`, `adapters/sqlite`, `apps/control-plane`.
- Quarkus REST + Vert.x, API de tareas, estados, scheduler, persistencia SQLite y resultado de modelo.
- `LocalModelClient`: cliente de inferencia local; Qwen/Ollama observado funcionando en capturas previas.
- Gateway de herramientas: `diagram.render` y `browser.inspect`, token y `approved=true`, salida en `.aies/artifacts`.
- `tools/aies_agent.py`: propuesta de **una** acción JSON mediante modelo local y aprobación CLI; no es multiagente.
- `tools/aies_tools.py`: PlantUML/Playwright locales, validaciones de rutas y URLs loopback.
- GitHub Actions: compilación pasó, pero la ejecución 38194163844 falló al inicializar Quarkus durante `TaskResourceTest` (ConfigurationException). No consta una ejecución verde posterior.

## Incluido en este ZIP v0.6

- `tools/aies_worktree.py`: creación de worktree aislado, propuesta de archivo textual allowlisted, revisión de diff, hash SHA-256 y aprobación explícita antes de crear un commit **en rama separada**.
- `tests/test_worktree_v06.py`: tests de validación de rutas e identificadores.
- `scripts/v06-smoke.sh`: ejecuta tests Python y Gradle.
- `examples/v06-proposal.java`: cambio de ejemplo inocuo.
- Documentación reproducible y flujo manual de validación.

## Estado real frente a la meta

| Capacidad | Estado | Próximo paso |
|---|---|---|
| REST Quarkus + persistencia de tareas | Existe | Contratos, pruebas de concurrencia y recuperación |
| Inferencia local | Existe | Benchmarks, límites de contexto y cancelación |
| Gateway PlantUML/Playwright | Existe | Auditoría SSRF, symlinks y procesos |
| CI | Bloqueada en última ejecución revisada | Corregir configuración de token y verificar pipeline verde |
| Git worktree y revisión de cambios | **Incluido v0.6, no probado en Fedora** | Integración E2E con repositorio real |
| Generación automática de parches por IA | Pendiente | Adaptador de cambios estructurados, nunca shell libre |
| Ejecución de tests sobre código generado | Pendiente | Podman rootless con red deshabilitada y límites |
| Rollback automatizado | Parcial | Remoción segura de worktree, limpieza y recuperación de runs |
| Aprobación segura multiusuario | Pendiente | Identidad, nonce, expiración, auditoría y RBAC |
| Multiagentes (arquitecto, dev, QA, reviewer, security) | Pendiente | Máquina de estados, contratos, presupuestos y evaluadores |
| Memoria por proyecto | Pendiente | SQLite FTS5, indexación incremental, privacidad |
| Observabilidad | Pendiente | OTel, métricas, trazas, dashboards y SLO |
| UX/UI visual multimodal | Pendiente | Modelo local de visión y benchmarks |
| Endurecimiento productivo | Pendiente | Threat model, rate limits, aislamiento OS, CI, load tests |

## Brechas críticas de seguridad

1. Worktree Git **no es sandbox de sistema operativo**. No ejecutar tests, scripts o builds de código desconocido fuera de un contenedor rootless restringido.
2. El gateway actual recibe `approved=true` del cliente: eso no prueba la identidad del aprobador. El token compartido puede autorizar llamadas sin workflow de revisión.
3. La validación de paths y URLs del gateway requiere pruebas adversariales; no asumir que el filtro PlantUML o Playwright sea una barrera completa.
4. No guardar `.env`, tokens, modelos o artefactos sensibles en commits.
5. El flujo v0.6 usa SHA-256 de un diff para comprobar integridad de aprobación, pero no implementa autenticación del aprobador ni firmas.

## Ruta para completar

- **v0.6.1:** CI verde, integración de worktree al control plane, suite E2E y aislamiento Podman para pruebas.
- **v0.7:** orquestación de roles, estados y aprobación persistente; generación de parches por modelo y evaluador QA.
- **v0.8:** memoria FTS5 por proyecto, recuperación de contexto, OpenTelemetry, auditoría y métricas.
- **v0.9:** análisis visual, accesibilidad y pruebas de UI sobre capturas.
- **v1.0:** hardening, pruebas de carga/resiliencia, documentación y distribución reproducible.

**Criterio de cierre de v0.6:** pipeline verde; crear propuesta, revisar diff, rechazar cambios no autorizados, aprobar hash, crear commit solo en rama aislada, recuperar/limpiar worktree y ejecutar pruebas de regresión. Este ZIP cubre el flujo manual central, pero NO todos los criterios de cierre.
