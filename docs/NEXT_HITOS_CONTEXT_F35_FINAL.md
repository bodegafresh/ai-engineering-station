# AIES — F3.5.1: Durable single-flight para documentación

## Base revisada

GitHub `main` commit `c032a3ba571db0b10c0b3451d67940d8d815fd9e`. Paquete **incremental**, no cierre certificado de F3.5 ni E2E.

## Implementado

- Tabla aditiva `execution_model_invocations` en `execution-handoff.sqlite`, con PK `(workflow_id, task_id, attempt_id)`.
- `ExecutionHandoffStore.reserveModelInvocation` valida grant durable, lease activo y journal `PREPARED`; `INSERT OR IGNORE` arbitra concurrencia entre conexiones/procesos.
- `WorkspaceDocumentationService.generate` reserva la invocación inmediatamente antes de `model.execute`. Un duplicado concurrente falla cerrado y no repite la solicitud al modelo.
- No cambia Event Bus, REST, payloads ni políticas de habilitación; SQLite y HTTP siguen ejecutándose en workers bloqueantes existentes.

## Limitación importante

Una caída **después de reservar y antes de escribir el archivo** deja una invocación ambigua. No se reenvía automáticamente el mismo intento porque podría duplicar un efecto externo. Un operador deberá reconciliar y crear un intento nuevo con autorización apropiada. El resultado es **at-most-once por intento**, no exactly-once de resultado. El método `modelInvocationReserved` permite diagnóstico sin alterar estado.

## Siguientes pasos P0 para cerrar F3.5

1. Pruebas de integración concurrentes con dos conexiones SQLite, dos solicitudes Event Bus y contador de llamadas al modelo: exactamente una reserva y máximo una llamada.
2. Reconciliación del caso crash entre publicación de archivo y `FILE_WRITTEN` usando staging con identidad/hash verificados; no aceptar archivos no journalizados como evidencia confiable.
3. Eliminar la posibilidad de bypass por `ExecutionHandoffStore.approveEvidence`: mover aprobación a frontera que verifique registro de auditoría `APPROVED`, subjectId, pertenencia, hash material y estado dentro de una operación controlada; migrar consumidores/tests antes de deshabilitar la API antigua.
4. Ensayos con lease vencido, fencing, modificación de archivo, reinicio de Quarkus y crash en cada frontera. La prueba `./gradlew clean test` es obligatoria.

## F4: E2E

- F4.1: Ollama HTTP simulado, workspace temporal, plan de dos tareas, audit y manifiesto final.
- F4.2: Ollama real con modelo fijado y verificación de salida material.
- F4.3: smoke automatizado, fallos inducidos y evidencia de aceptación de extremo a extremo.

## F5/F6

- F5: herramientas de ingeniería bajo autorización explícita, sin shell arbitrario.
- F6: observabilidad, backpressure, métricas, trazas y compatibilidad Gradle 9.

## Validación

```bash
./gradlew :apps:control-plane:test --tests '*ExecutionIdempotencyTest' --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
git diff --check
```

**No se ha ejecutado Gradle sobre un checkout completo en este entorno.**
