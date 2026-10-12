# AI Engineering Station v0.16 — memoria y capacidades (fundación)

## Alcance implementado
- Memoria **volátil** aislada por proyecto, límite de entradas y recuperación léxica determinista. No persiste entre reinicios.
- Política de capacidades `DENY` por defecto, permisos por especialista y operación.
- `ToolGateway` con verificación de aprobación **externa** y auditoría de resultados (inyectadas por el runtime).
- Tests unitarios de aislamiento, límites, permisos y aprobación.
- No se modifican endpoints existentes, orquestador v0.15 ni selección de modelos.

## Límites de seguridad y trabajo pendiente
- **NO** hay integración real con Google Docs/Drive, Grafana, Loki, Prometheus o Telegram.
- **NO** hay almacén seguro de credenciales, autenticación, OAuth ni verificador de aprobación productivo.
- **NO** usar un `ApprovalVerifier` que apruebe todo; la aprobación debe estar ligada a usuario, operación, alcance, caducidad y nonce de un solo uso.
- **NO** aceptar URLs, rutas, consultas arbitrarias ni secretos del prompt del agente. Adaptadores deben validar destino, operación y filtros; limitar alcance, tiempo y volumen.
- El permiso `ALLOW_READ` exige nombre terminado en `-read`; **no** implica que el adaptador sea de solo lectura. El adaptador debe hacer cumplir esa propiedad.
- `AuditSink` debe persistir auditoría **sin secretos**; no usar salida del modelo como evidencia verificada.
- `ProjectMemory` es un prototipo sin persistencia, ranking semántico, borrado normativo, control de acceso por usuario ni sanitización de secretos. No almacenar datos sensibles sin controles adicionales.

## Especialistas propuestos para una siguiente integración del catálogo
- `sre-engineer`: SLO/SLI, alertas, dashboards, observabilidad, diagnósticos, runbooks, capacidad, guardias.
- `incident-commander`: severidad, declaración, responsables, coordinación, cronología, comunicaciones, escalamiento, mitigación y cierre.
- `postmortem-analyst`: reconstrucción verificable de línea temporal, causas contribuyentes, impacto, aprendizajes, acciones con responsables y plazos; enfoque sin culpabilización.

Estos perfiles se documentan aquí pero **no se registran aún** en `catalog.json`, para no romper la correspondencia estricta entre catálogo, perfiles y plantillas Velocity.

## Plan de adaptadores y privilegios
| Capacidad | Especialista típico | Política inicial | Requisito |
|---|---|---|---|
| `grafana-read` | sre-engineer | ALLOW_READ (opt-in) | token de servicio solo lectura, tenant y dashboard allowlist |
| `logs-read` | sre-engineer | ALLOW_READ (opt-in) | consultas acotadas, redacción de secretos/PII, rate limit |
| `google-docs-read` | market-researcher | ALLOW_READ (opt-in) | OAuth con scopes mínimos, documento allowlist |
| `google-docs-write` | documentation-specialist | REQUIRE_APPROVAL | revisión de diff, permisos de documento, versionado |
| `telegram-send` | incident-commander | REQUIRE_APPROVAL | chat allowlist, contenido revisado, idempotencia |
| `restart-service` | sre-engineer | REQUIRE_APPROVAL | runbook, ventana, doble confirmación, rollback |

## Siguiente fase propuesta
1. Persistencia SQLite FTS5 por proyecto con migraciones, ACL, cifrado en reposo según amenaza, retención, borrado y presupuesto de contexto/tokens.
2. Definir contrato de proveedor de secretos; OAuth Google con scopes mínimos y refresh seguro; token de Grafana/Loki y Telegram fuera del repositorio.
3. Adaptadores de lectura con timeouts, circuit breakers, límites de consulta y auditoría; pruebas con servidores simulados.
4. Flujo de aprobación humana firmado/expirable; colas durables, retries e idempotencia para notificaciones.
5. Registrar los 3 perfiles, plantillas `.vm`, routing y revisores en un mismo incremento atómico.
6. Pipeline de incidentes: alerta → correlación → diagnóstico → propuesta → aprobación → acción → verificación → postmortem.

## Verificación
`./gradlew :apps:control-plane:test --tests 'dev.aies.control.capabilities.CapabilityPolicyTest' --tests 'dev.aies.control.memory.ProjectMemoryTest' --no-daemon --console=plain`

`./gradlew clean test --no-daemon --console=plain && bash scripts/v07-smoke.sh && git diff --check`
