# AI Engineering Station

Estación de desarrollo multiagente con IA local y herramientas gratuitas, optimizada para Linux, con memoria persistente, automatización y observabilidad.

> **Estado:** Fase 01 — esqueleto funcional de orquestación. **Todavía NO incluye agentes de IA, modelos, RAG, sandbox ni seguridad de producción.**

## Arquitectura

- `modules/domain`: entidades y reglas puras.
- `modules/orchestration`: puertos y casos de uso independientes de frameworks.
- `adapters/sqlite`: persistencia de tareas en SQLite.
- `apps/control-plane`: Quarkus REST, Vert.x Event Bus y reconciliador periódico.
- `docs/`: ADR y límites explícitos.

## Requisitos

- Fedora (o Linux), JDK 21, `curl` y `unzip`.
- Conexión a internet inicial para descargar Gradle y dependencias Maven.
- Gradle 8.14.3, Quarkus 3.27.1 (versiones iniciales para validar; no se afirma compatibilidad con Java 25).

## Preparación en Fedora

```bash
sudo dnf install -y java-21-openjdk-devel curl unzip git
./scripts/fedora-check.sh
./scripts/bootstrap-gradle.sh
./gradlew test
```

**Nota:** El ZIP no incluye `gradle-wrapper.jar` oficial porque no estaba disponible en el entorno de generación. El script `bootstrap-gradle.sh` descarga Gradle 8.14.3 desde `services.gradle.org` y genera el wrapper oficial con la tarea `wrapper`. Antes de usar el proyecto en CI público, versiona `gradle/wrapper/gradle-wrapper.jar` y configura verificación de checksums de la distribución. El primer bootstrap exige conexión de red.

## Iniciar el servicio

```bash
./gradlew :apps:control-plane:quarkusDev
```

En otra terminal:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"objective":"Preparar una propuesta de arquitectura"}'

# Sustituir UUID por el identificador devuelto:
curl http://127.0.0.1:8080/api/v1/tasks/UUID
```

La tarea se guarda en SQLite, se notifica por Vert.x Event Bus y el consumidor **simulado** la marca COMPLETED. Si se pierde la notificación, un reconciliador revisa las tareas pendientes cada 3 segundos. **COMPLETED no significa que un agente haya implementado nada** en esta fase.

## Próximas fases

1. Leases, outbox, checkpoints, idempotencia y pruebas de fallos.
2. Adaptador de inferencia llama.cpp Vulkan y Model Router.
3. Agentes Planner/Architect/Developer/QA, Git worktrees y Podman rootless.
4. Memoria por proyecto, RAG y skills.
5. Observabilidad OpenTelemetry y dashboard.

## Seguridad

API únicamente en `127.0.0.1`. No exponer a redes externas; no tiene autenticación. No se ejecutan comandos generados por IA. No guardar secretos en Git.

## Licencia

No se incluye licencia; el propietario del repositorio debe escogerla expresamente.
