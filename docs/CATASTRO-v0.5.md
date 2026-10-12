# AI Engineering Station — catastro y hoja de ruta (12 oct 2026)

## Alcance de la evidencia
Basado en los parches v0.2-v0.4 disponibles y en los resultados de terminal compartidos. No es una auditoría completa del HEAD actual del repositorio. Las funciones marcadas como «observado» se verificaron mediante capturas, no mediante ejecución de este parche en Fedora.

| Capacidad | Situación | Evidencia y límites |
|---|---|---|
| Monorepo Gradle Java / Quarkus / Vert.x | Implementado | Compilación y tests exitosos observados en v0.4; no se revisó HEAD remoto |
| Tareas REST y estados | Implementado | POST, GET, GET result observados |
| Persistencia SQLite, outbox, leases | Implementado en v0.3 | Pruebas de regresión previas; requiere auditoría de concurrencia y fallos |
| Ollama Qwen 2.5 Coder 7B local | Implementado | COMPLETED y respuesta persistida observados |
| Scheduler sin bloqueo de event loop | Implementado | executeBlocking en TaskRuntime; falta prueba de carga |
| Herramienta Playwright local | Implementado v0.4 | Página localhost inspeccionada con HTTP 200 y PNG |
| PlantUML y Graphviz | Implementado v0.4 | SVG generado |
| Restricciones de herramientas | Parcial | Tests Python de política, no sustituyen aislamiento OS |
| Gateway REST con aprobación y token | **Incluido en parche v0.5** | Requiere compilar y validar en Fedora |
| Propuesta de herramientas mediante Qwen | **Incluido en parche v0.5** | JSON validado; requiere revisión humana antes de ejecutar |
| Ejecución autónoma de múltiples herramientas | Pendiente | v0.6: máquina de estados y permisos por paso |
| Escritura de código y edición Git | Pendiente | v0.6+, sandbox con diff y aprobación |
| Multiagentes arquitecto/dev/QA/seguridad | Pendiente | v0.7+; no confundir prompt único con agentes reales |
| Memoria semántica por proyecto | Pendiente | SQLite FTS5 + indexación incremental y reglas de privacidad |
| Evaluación visual multimodal | Pendiente | Captura existe, pero Qwen2.5-Coder 7B no interpreta imágenes directamente |
| Métricas, trazas, costes y SLO | Parcial/pendiente | Sin instrumentación end-to-end verificada |
| CI, seguridad y aislamiento fuerte | Parcial | CI básica; falta Podman rootless/seccomp y pruebas adversariales |

## Arquitectura v0.5
Quarkus ToolGatewayResource (REST local, token, aprobación explícita) -> proceso Python allowlisted -> PlantUML / Playwright -> artefactos bajo .aies/artifacts.
En paralelo, tools/aies_agent.py -> Ollama -> propuesta JSON validada -> archivo local -> aprobación humana -> gateway.

## Restricciones y riesgos conocidos
- Gateway deshabilitado por defecto; requiere `AIES_TOOLS_ENABLED=true` y token aleatorio largo.
- API Quarkus debe seguir vinculada a 127.0.0.1; no exponer en red ni por proxy.
- El campo `approved=true` más el token representan autorización explícita del operador, **no** un sistema de aprobación multiusuario.
- Los procesos de herramientas aún no están aislados mediante contenedores; ejecutar solo en proyectos y sitios locales confiables.
- Playwright puede ejecutar JavaScript de la página; no abrir sitios no confiables.
- PlantUML se filtra con una lista restrictiva básica; no es un parser de seguridad formal.
- No habilitar comandos arbitrarios ni acceso a URLs públicas en esta versión.
- El código del parche no ha sido compilado en el Fedora del usuario; las pruebas finales son obligatorias.

## Próximas versiones
- v0.6: sandbox rootless, propuestas de cambios en worktree Git, diff, pruebas y rollback, aprobaciones por acción.
- v0.7: roles separados y orquestación con presupuestos de contexto, persistencia de runs y recuperación.
- v0.8: memoria por proyecto con FTS5, indexación de código, trazas OpenTelemetry y métricas.
- v0.9: evaluación UX/UI con modelo de visión local compatible, comparativas de capturas, accesibilidad automatizada.
- v1.0: pruebas de estrés, seguridad, resiliencia, empaquetado reproducible y documentación operativa.
