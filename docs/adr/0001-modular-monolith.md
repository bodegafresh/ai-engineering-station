# ADR-0001: Monolito modular con Quarkus + Vert.x
Estado: Aceptado para POC.

Contexto: estación multiagente local en Fedora con control de costos y herramientas heterogéneas.

Decisión: Java 21 para bootstrap, Quarkus como runtime y Vert.x Event Bus como notificación interna. Módulos domain y orchestration sin dependencias de Quarkus. SQLite como almacenamiento durable local.

Consecuencias: despliegue simple; interfaces portables; Event Bus no es cola durable. Java 25 y nuevas versiones se evaluarán con benchmarks y compatibilidad.
