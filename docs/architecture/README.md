# Arquitectura — AI Engineering Station

## Principios

- El núcleo de dominio NO importa Quarkus, Vert.x ni frameworks de los proyectos de usuario.
- Vert.x Event Bus comunica componentes dentro del proceso; no es almacenamiento durable.
- SQLite persiste tareas. El reconciliador recupera PENDING tras reinicio.
- El primer consumidor es **simulado**: pasa PENDING a COMPLETED. No invoca modelos ni herramientas.
- En la siguiente fase se implementarán leases, outbox transaccional, idempotencia de efectos externos, auditoría y permisos.
- La plataforma debe ser agnóstica a Java, C++, Python, TypeScript y demás stacks de proyectos.

## Límites del POC

Solo un proceso Quarkus, sin workers distribuidos. El evento puede perderse, pero el polling SQLite recupera tareas pendientes. No hay autenticación, aislamiento ni inferencia todavía. La API se enlaza a 127.0.0.1 por defecto; NO exponer a internet.
