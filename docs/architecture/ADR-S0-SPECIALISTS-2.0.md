# ADR S0 — AIES Specialists 2.0 (propuesta, sin cambios de runtime)

Estado: **PROPOSED — pendiente de aprobación del operador**. Base: rama `main`, inspeccionada el 14-10-2026. Alcance: S0 contractual, sin activar S1 ni cerrar F4.

## ADR-001: composición de competencias
Un `SpecialistProfile` es un manifiesto versionado de capacidades, rol, dominio, jurisdicción, métodos y restricciones. No es un modelo de IA, una persona licenciada ni un grant. Las capacidades son referencias exactas, sin herencia implícita; no crear una clase Java por profesión. La tarea solicita un conjunto explícito de capacidades y puede dividirse en tareas multidisciplinarias en hitos posteriores.

## ADR-002: gobierno y publicación
Estados previstos: `DRAFT`, `VALIDATED`, `APPROVED`, `ACTIVE`, `REJECTED`, `DEPRECATED`, `REVOKED`. Solo perfiles `ACTIVE` elegibles. Una propuesta IA jamás se autoaprueba. Aprobación vinculada a identidad autenticada, hash del manifiesto y revisión CAS. Contenido publicado inmutable; cambios crean nueva versión. Revocación bloquea nuevas asignaciones y exige reconciliación de intentos en vuelo; no promete cancelar efectos externos.

## ADR-003: selector determinista y compatibilidad
Preservar `aies.specialists.select.v1` con `capabilities`, `permittedTools`, `budget` y salida `selected`, `specialistId`, `costUnits`. Los cuatro perfiles semilla son `researcher` (`research`, 1), `requirements-auditor` (`requirements`, `audit`, 2), `software-engineer` (`software`, `implementation`, 3) y `experiment-analyst` (`statistics`, `experiments`, 2). Sus concurrencias son 2, 1, 1, 1 respectivamente. Selección legado: capacidades requeridas incluidas, herramientas requeridas del especialista incluidas en permitidas, costo <= presupuesto; ordenar costo, luego ID. Registro S1 bajo `aies.specialists.registry.enabled=false` por defecto; si se habilita y falla, no volver silenciosamente al catálogo legado.

## ADR-004: perfiles y modelos separados
`SpecialistProfile` define competencias y políticas. `ModelProfile` y evaluación por competencia son S2, no S0/S1. La identidad y revisión real del modelo deben registrarse por ejecución futura. Un rol `AUDITOR` no confiere aprobación.

## ADR-005: autonomía basada en riesgo
R0 informativo, R1 bajo, R2 moderado, R3 alto, R4 crítico. Política efectiva: intersección de sistema, operador, workflow, perfil y herramienta; una denegación prevalece. El riesgo se clasifica por acción; una clasificación automática no puede rebajar mínimos obligatorios. Acciones externas y decisiones reguladas de alto impacto requieren controles y revisión humana adecuados.

## Decisiones abiertas que bloquean S1
1. Especificar canonicalización JSON exacta y vectores de hash entre Java y CLI; SHA-256 de bytes sin canonicalización definida no basta.
2. Identificar autorizar real del operador en `main` y diseñar scopes separados para proponer, aprobar, activar y revocar.
3. Resolver revisión global vs por agregado e invalidación de cachés.
4. Definir retención/privacidad de decisiones de selección y mensajes.
5. Resolver semántica de revocación durante ejecución y revalidación en puntos de efecto.
6. Diseñar persistencia/outbox, migración, backups y rollback reversible con pruebas multiproceso cuando aplique.

## Contratos y límites de S0
- `docs/contracts/specialist-profile-v2.schema.json`: forma documental para perfiles propuestos, **no** manifiesto publicable validado por runtime.
- `docs/contracts/specialist-selection-v2.schema.json`: contrato de selección propuesto, sin nuevo consumidor Event Bus.
- Fixtures de perfiles legados y pruebas de contrato shell de S0. No se crean perfiles jurídicos activos, ni se conceden herramientas.
- Ningún esquema JSON sustituye validación semántica de referencias, autorizaciones, roles, fuentes, DAG o integridad durable.

## Criterios de aceptación S0
S0-AC01: cinco ADR revisados. S0-AC02: contratos con fixtures válidos e inválidos. S0-AC03: semillas equivalentes a `main`. S0-AC04: compatibilidad v1 y plan de migración reversible. S0-AC05: amenazas y autorización documentadas. S0-AC06: pruebas deterministas y negativas reproducibles. S0-AC07: sin cambios de runtime y F4 abierto.

## Criterios de aceptación futuros S1 (no implementados)
S1-AC01 registro SQLite versionado y hashes; AC02 equivalencia v1; AC03 propuestas IA solo DRAFT; AC04 autorización de activación; AC05 inmutabilidad; AC06 no elegibilidad explícita; AC07 revocación/deprecación; AC08 CAS concurrente; AC09 reinicio durable; AC10 fail-closed ante corrupción; AC11 sin secretos; AC12 regresión completa; AC13 rollback por flag sin borrar historial; AC14 documentación asignable solo con capacidad declarada activa.

## Migración propuesta S1
(1) Congelar fixtures y tests v1. (2) Crear tablas sin cambiar selector activo. (3) Importar cuatro perfiles con IDs, costos, herramientas y concurrencia exactos, transacción e idempotencia. (4) Comparar modo sombra. (5) Activar flag tras comprobación de integridad. (6) Revertir flag sin borrar historial ni reescribir workflows persistidos.

## Modelo de amenazas S0
Prompt injection en propuestas; suplantación de aprobador; modificación TOCTOU del manifiesto; escalamiento por capacidades o aliases; confused deputy entre herramientas solicitadas y grants; replay, duplicados, carreras CAS, datos sensibles en logs, corrupción de registro, revocación durante efectos externos, saturación y ciclos de referencias. Mitigación prevista: entrada no confiable, validación estricta, scopes, hashes canónicos, idempotencia, CAS, separación de funciones, límites y rechazo seguro.

## Pruebas S1 requeridas
Unitarias de estado y elegibilidad; SQLite con migración idempotente, rollback y restart; Event Bus v1/v2; concurrencia CAS; autorización y revocación; fallos de DB; hashes alterados; negativos y `./gradlew clean test`. No declarar resultados no ejecutados.
