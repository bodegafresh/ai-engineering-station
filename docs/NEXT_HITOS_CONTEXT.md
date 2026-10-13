# AIES — Estado del Hito B y continuidad

## Baseline

Repositorio `bodegafresh/ai-engineering-station`, rama `main` consultada el 2026-10-13. Java 21, Quarkus 3.27.1, Vert.x Event Bus, Gradle, SQLite. P0.14–P0.17 intake/plan/auditoría; Hito A P1.1–P1.2 metodología y requisitos. No cambiar contratos existentes, ni asumir que auditoría equivale a ejecución.

## Este paquete: P1.3 y segmento seguro de P1.4

- `ExecutionGraph`: orden topológico determinista; detecta ciclos, referencias ausentes, tareas sin especialista, duplicados y presupuesto excedido.
- `ExecutionHandoffStore`: SQLite independiente `execution-handoff.sqlite`; importación idempotente; tareas `PENDING`, `LEASED`, `COMPLETED`; token y vencimiento de lease; reintentos acotados; evidencia obligatoria para completar; desbloqueo por dependencias.
- `ExecutionHandoffResource`: endpoint operador para importar **solo** planes con auditoría aprobada (`READY_FOR_ASSIGNMENT` y audit ID); consulta de tareas. Habilitación separada, apagada por defecto.
- Pruebas JUnit de DAG, persistencia entre aperturas, idempotencia, rechazo de tokens erróneos, dependencias, conflictos y ciclos.

**No se incluye ejecución automática de especialistas.** `claim` y `complete` son primitivas de persistencia internas sin exposición REST; deben ser consumidas por el futuro orquestador de ejecución por Vert.x Event Bus, con permisos y evidencia real. No afirmar que P1.4 está finalizado.

## Instalación y pruebas

Desde una rama limpia basada en main:

```bash
unzip aies-hito-b.zip -d /tmp
bash /tmp/aies-hito-b/scripts/install.sh
./gradlew :apps:control-plane:test --tests '*ExecutionGraphTest' --tests '*ExecutionHandoffStoreTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
```

El instalador no sobreescribe Java existente. Agrega una propiedad a `application.properties`; verificar que no esté duplicada antes del merge. Ejecutar smoke de los endpoints existentes con configuración de seguridad real.

## Próximos pasos, en orden

1. **P1.4**: conectar el orquestador al Event Bus con comandos versionados `execution.claim`, `execution.complete`, `execution.fail`, autorización por capacidad y herramientas permitidas, bounded concurrency, idempotencia y outbox/inbox. Nunca ejecutar código, redes o despliegues por un simple `import`.
2. **P1.4**: implementación de `fail` con clasificación recuperable/no recuperable, dead-letter y recuperación tras reinicio. Resolver tareas que agotan intentos y propagación de bloqueos a dependientes. No simular éxito con referencias de evidencia inventadas.
3. **P1.5**: calidad específica por metodología; verificación de evidencia antes de `COMPLETED`; auditoría posterior a ejecución y gate de entrega. Conservar rubricas científicas solo para experimentos.
4. **P1.6**: métricas, trazas correlacionadas, alertas de cola, auditoría y costo; tests E2E de reinicio, concurrencia multi-instancia, timeout, replay, caída entre escritura y publicación.
5. **Requisitos v3**: extracción asistida por modelo con citas de fuentes verificables, contradicciones semánticas y revisión humana de incertidumbre; no reemplazar el intake determinista.

## Riesgos pendientes

- Importación entre `planning.sqlite` y `execution-handoff.sqlite` no es una transacción distribuida; el reintento idempotente reduce riesgo pero no lo elimina.
- `complete` comprueba formato de referencia, **no existencia ni calidad de la evidencia**. Conectar `EvidenceVerifier` antes de habilitar consumidores.
- El store usa una conexión sincronizada por instancia, no una cola transaccional multi-proceso; antes de multi-instancia implementar claim atómico y coordinación.
- `claim` puede volver a arrendar tareas cuyo lease expiró, hasta el límite de intentos. Falta política de dead-letter y bloqueo explícito.
- `PlanningEventBus` produce Markdown con secuencias literales `\\n`; corregir sin alterar payloads históricos.
- Auditoría: verificar idempotencia al crear artifacts y manejo de errores/timeout.
- `ExecutionGraph` usa `plan.estimatedCostUnits`; añadir verificación del total recalculado de tareas y coherencia con presupuesto antes de habilitar ejecución.

## Criterios de salida del Hito B completo

Pruebas verdes; solo planes auditados entran; cada tarea se asigna a un especialista autorizado; toda ejecución pasa por Event Bus; una tarea no corre antes de sus dependencias; los fallos no duplican efectos; evidencia verificable precede `COMPLETED`; reinicios no pierden trabajo; costo y concurrencia están limitados; se puede auditar cada decisión.
