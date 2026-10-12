# AI Engineering Station v0.7 — Catastro y hoja de ruta

## Verificado en evidencia del usuario
- v0.5: gateway REST de herramientas, PlantUML SVG, 200 OK y 401/403 de rechazo.
- v0.6: worktree aislado; propuesta, revisión, aprobación por SHA-256 y commit `COMMITTED_IN_WORKTREE`, sin merge automático.
- Quarkus 3.27.1 inicia y ejecuta tareas locales; Python v0.6: 3/3 pruebas.
- CI anterior falló por configuración de pruebas; **no hay evidencia de CI verde actual**.

## Entregado en este overlay v0.7
- Adaptador de inferencia con proveedor `local` predeterminado.
- Conectores directos a OpenAI, Anthropic Claude, DeepSeek, Qwen/DashScope, Moonshot/Kimi y Zhipu/GLM.
- Credenciales solo por variables de entorno; no en repositorio.
- Cloud deshabilitada por defecto; **no hay fallback automático que genere gastos**.
- Límite de tokens de salida y timeout; errores sin cuerpo HTTP para evitar filtrar secretos.
- Sin nuevas dependencias Gradle; se mantiene API `complete(String)` y `Completion` del scheduler.

## Limitaciones y pendientes — prioridad
1. P0: pruebas unitarias con servidor HTTP simulado, tests de seguridad, y CI verde.
2. P0: presupuestos de costo por proveedor y por tarea, cuotas, auditoría de consentimiento, rate limits y circuit breakers.
3. P0: routing por rol y tarea (`architect`, `coder`, `reviewer`, `qa`) con política explícita y fallback sujeto a aprobación.
4. P0: controles de datos: clasificación local/confidencial, redacción de PII/secretos antes de la nube, protección de prompt injection.
5. P1: benchmark reproducible por modelo (calidad, latencia, VRAM, tokens/s, TTFT, costo) y catálogo versionado de capacidades.
6. P1: memoria por repositorio y recuperación híbrida SQLite FTS5 + embeddings; compactación de contexto y caché.
7. P1: API de invocación con streaming SSE y métricas OpenTelemetry (sin guardar prompts sensibles).
8. P1: múltiples proveedores con compatibilidad real de API y detección de modelos descontinuados.
9. P1: ejecución multiagente con límites de concurrencia, sandbox rootless y revisión humana.
10. P2: interfaz web de comparación, evaluación ciega y panel de presupuestos.

## Proveedores y perfiles sugeridos
| Rol | Preferencia inicial | Alternativas | Política |
|---|---|---|---|
| Tareas simples, resúmenes, clasificación | Qwen local / llama.cpp | GLM, DeepSeek | local por defecto |
| Implementación de código | Qwen coder local | Claude, OpenAI, DeepSeek | nube solo aprobada |
| Arquitectura y revisión profunda | modelo local de mayor capacidad | Claude, OpenAI, Kimi | no enviar código privado sin aprobación |
| QA, tests y documentación | Qwen local | DeepSeek, Qwen Cloud | límites por tarea |

Los modelos concretos son configurables y sujetos a disponibilidad, costo, región y API del proveedor.

## Operación
- `AIES_AI_DEFAULT_PROVIDER=local` (predeterminado)
- `AIES_AI_CLOUD_ENABLED=false` (predeterminado)
- `AIES_AI_MAX_OUTPUT_TOKENS=512` (predeterminado)
- `AIES_AI_DEFAULT_PROVIDER=anthropic AIES_AI_CLOUD_ENABLED=true ANTHROPIC_API_KEY=...` habilita Claude.
- Variables de credenciales: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `DEEPSEEK_API_KEY`, `DASHSCOPE_API_KEY`, `MOONSHOT_API_KEY`, `ZHIPU_API_KEY`.
- Modelos: `AIES_OPENAI_MODEL`, `AIES_ANTHROPIC_MODEL`, `AIES_DEEPSEEK_MODEL`, `AIES_QWEN_MODEL`, `AIES_MOONSHOT_MODEL`, `AIES_ZHIPU_MODEL`.
- **No ejecutar proveedores de nube en repositorios privados sin consentimiento y presupuesto.**

## Compatibilidad
Overlay preparado sobre `feat/worktree-v06` según archivos recuperados de GitHub. No se ha ejecutado Gradle en el entorno Fedora del usuario. Verificar compilación y pruebas localmente antes de commit.
