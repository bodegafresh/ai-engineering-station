# Próximo paso: v0.5 Quarkus + tool calling

No se incluyen modificaciones a clases Java porque el código actual del repositorio no está disponible para verificar firmas y dependencias; aplicar cambios a ciegas reproduciría errores de compilación de v0.3.

## Contrato propuesto

- `ToolDefinition`: name, description, JSON schema, risk level.
- `ToolInvocation`: taskId, stepId, toolName, arguments, approvalId, deadline.
- `ToolExecutionResult`: success, stdout JSON, artifact paths, duration, error code.
- `ToolPolicy`: allowlist por proyecto, límites de tiempo, red, archivos y presupuesto.
- `ToolExecutor`: subprocess controlado o proceso aislado, nunca shell arbitraria.
- `ToolResultRepository`: persistencia idempotente en SQLite por `(taskId, stepId)`.

## Flujo

1. Qwen devuelve una solicitud estructurada de herramienta (no texto libre ejecutable).
2. Quarkus valida schema, workspace, política y aprobación humana para escritura/acciones riesgosas.
3. Ejecuta la herramienta aislada, captura JSON y registra artefactos.
4. Persistencia durable de cada paso antes de devolver el resultado al modelo.
5. Qwen revisa evidencia y propone siguiente paso con límite de iteraciones.

## UX/UI y visión

Playwright aporta DOM, screenshot, métricas básicas y, posteriormente, pruebas E2E. Para **evaluar realmente imágenes/capturas**, se requiere un modelo local con visión compatible con Ollama y capacidad comprobada de interpretar capturas; Qwen2.5-Coder:7b no debe considerarse un modelo visual. Un agente UX deberá combinar evidencia DOM, screenshot y checklist de heurísticas. Axe-core puede aportar análisis automático de accesibilidad, sin reemplazar pruebas manuales.

## Seguridad antes de autonomía

Rootless Podman con red denegada o allowlist, mounts de solo lectura, workspace temporal por tarea, recursos acotados, timeouts, no credenciales, registro de auditoría, límites de tokens y aprobaciones. Para navegación remota, añadir proxy filtrado y políticas SSRF; no habilitar por defecto.

## Criterios de aceptación v0.5

- Tests de contrato y de políticas (rutas, SSRF, ejecución, reintentos, cancelación).
- Un agente solicita renderizar `examples/architecture.puml` y recibe SVG.
- Un agente inspecciona una UI local y obtiene screenshot + metadatos.
- Ninguna herramienta puede escribir fuera de `.aies/artifacts/`.
- Reintentar una tarea no duplica ejecuciones.
- Quarkus inicia y pasa `./gradlew clean test` y `./gradlew clean build`.
