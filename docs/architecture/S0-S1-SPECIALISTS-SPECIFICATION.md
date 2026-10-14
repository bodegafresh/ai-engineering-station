# AIES — Especificación técnica S0/S1: Specialists 2.0

**Estado:** propuesta de arquitectura para aprobación, no implementada.  
**Base:** `main` revisada el 14-10-2026; verificar diferencias antes de codificar.  
**Decisiones:** composición de competencias; catálogo inicial y propuestas de IA sujetas a aprobación; coordinador dinámico con reglas deterministas; autonomía por riesgo.

## 1. Objetivo y alcance

Construir un registro durable, versionado y gobernado de perfiles de especialistas y competencias que sustituya la lista fija de `SpecialistEventBus` sin romper el protocolo de selección ni los workflows persistidos. Un especialista es un perfil de competencias y restricciones; no es un modelo ni una credencial profesional humana. Un perfil puede tener varias competencias; una tarea requiere un conjunto explícito de competencias. La elegibilidad es determinista y *fail closed*.

**S0:** ADR, esquema canónico, taxonomía y compatibilidad, modelo de amenazas, fixtures, pruebas contractuales y plan de migración; sin cambio de comportamiento productivo.  
**S1:** persistencia del registro, propuestas, validación, aprobación, activación, desactivación/revocación, selección compatible por Event Bus, endpoints de operador autorizados, pruebas y migración reversible.  
**Fuera de alcance:** asignación dinámica de modelos, creación autónoma de especialistas activos, autorización de herramientas, orquestación de equipos multidisciplinarios, certificación profesional, auditoría semántica profunda, ejecución de efectos externos y cierre de F4.3.

## 2. Evidencia de `main` y brechas

- `SpecialistCatalog.Specialist(id, capabilities, tools, maxConcurrent, costUnits)` y `select(...)` implementan un conjunto de capacidades, herramientas y presupuesto; seleccionan por costo e ID.
- `SpecialistEventBus` declara cuatro perfiles en memoria: `researcher` (`research`), `requirements-auditor` (`requirements`, `audit`), `software-engineer` (`software`, `implementation`), `experiment-analyst` (`statistics`, `experiments`). El consumidor `aies.specialists.select.v1` solo se registra cuando `aies.specialists.enabled=true`.
- `MethodologyRouter` reconoce `DOCUMENTATION` pero no existe especialista con capacidad `documentation` en ese catálogo; el resultado observado fue `BLOCKED_SPECIALIST`.
- `PlanningPolicy.review` rechaza requisitos críticos cuya verificación sea `INSPECTION`; `PlanningEventBus` solo selecciona especialistas si la revisión preliminar está aprobada.
- `RequirementsAssessment` realiza comprobaciones estructurales básicas, no validación profesional o semántica.
- La documentación canónica exige Java 21, Quarkus, Event Bus, SQLite, inmutabilidad, autorización explícita, no bloquear event loops y no declarar aceptación sin evidencia real.

Estos hechos son el estado observado de `main`, no una garantía sobre la rama local en desarrollo.

## 3. ADR-001 — Perfiles compuestos, no clases por oficio

**Decisión:** representar profesiones y especialidades como metadatos; competencias como referencias tipadas versionadas; perfiles como conjuntos de competencias declaradas. No crear una subclase Java por oficio. No inferir automáticamente competencias por parentesco taxonómico.

**Razón:** un perfil puede combinar derecho contractual y privacidad, o ingeniería y análisis de datos; una tarea interdisciplinaria puede requerir múltiples perfiles en hitos futuros. La composición reduce explosión combinatoria y mantiene trazabilidad.

**Consecuencias:** se requiere un registro de competencias y validación de referencias; las competencias declaradas no son prueba de desempeño; las evaluaciones de modelos se incorporarán posteriormente.

**Alternativas rechazadas:** un perfil universal; una clase por oficio; herencia transitiva implícita de permisos o competencias.

## 4. ADR-002 — Publicación gobernada y versiones inmutables

**Decisión:** `DRAFT -> VALIDATED -> APPROVED -> ACTIVE`, con `REJECTED` para propuestas rechazadas, `DEPRECATED` para retiro ordinario y `REVOKED` para bloqueo de seguridad. No se permiten transiciones arbitrarias. Cada versión publicada conserva su contenido y SHA-256. Cambios sustantivos crean una versión nueva. La aprobación y activación requieren actor autorizado; una propuesta de IA nunca se autoaprueba.

**Precisiones:** `APPROVED` y `ACTIVE` son operaciones distintas; una revocación debe impedir nuevas asignaciones inmediatamente y disparar revisión de intentos pendientes; no alterar retrospectivamente decisiones históricas. Las transiciones se hacen con CAS/versionado optimista y evento durable.

## 5. ADR-003 — Selección determinista con compatibilidad v1

**Decisión:** separar **filtro de elegibilidad** de **ordenamiento**. Elegibilidad exige perfil `ACTIVE`, conjunto completo de capacidades, compatibilidad de herramientas, presupuesto, políticas aplicables y ausencia de revocación. En S1 conservar la regla histórica de costo ascendente e ID para perfiles migrados; no añadir heurísticas de IA al selector. Retornar motivos codificados de rechazo sin exponer información sensible.

**Compatibilidad:** mantener `aies.specialists.select.v1` y sus campos `capabilities`, `permittedTools`, `budget`; conservar respuesta `selected`, `specialistId`, `costUnits`. Extender mediante evento `aies.specialists.select.v2` o campos opcionales compatibles, sin cambiar el significado de v1. La selección v1 debe seguir usando semántica de conjunto exacta para capacidades heredadas. No permitir que un alias jerárquico conceda competencias adicionales.

**Activación:** feature flag `aies.specialists.registry.enabled=false` por defecto, con adaptador legado cuando esté deshabilitado. No usar fallback al catálogo antiguo cuando el registro esté habilitado pero falle la base de datos o su integridad: fallar cerrado. El catálogo inicial se carga por migración idempotente y comprobada, no por creación de perfiles activos a través de IA.

## 6. ADR-004 — Modelo de ejecución desacoplado

**Decisión:** `SpecialistProfile` describe rol, competencias, metodología y políticas; `ModelProfile` será otro agregado. S1 no selecciona modelos ni cambia Ollama. Las futuras asignaciones congelarán referencias y digests de perfil, modelo, política y metodología por intento. Un rol como `AUDITOR` no concede automáticamente permiso para aprobar.

## 7. ADR-005 — Riesgo y autorización independientes

**Decisión:** registrar metadatos de riesgo y restricciones en el perfil, pero no interpretar esos metadatos como permisos. El permiso efectivo es la intersección de política del sistema, autorización del operador, restricciones del workflow, restricciones del perfil y autorización de herramienta. Toda regla denegatoria prevalece. En S1 no se conceden nuevas capacidades materiales.

Clasificación prevista: R0 informativo, R1 bajo, R2 moderado, R3 alto, R4 crítico. La clasificación futura será por acción y podrá imponer revisión humana; no reducirla mediante promedios. Para derecho, finanzas, privacidad y otros dominios sensibles, jurisdicción y límites de uso son atributos obligatorios cuando corresponda. No se presenta al modelo como abogado o contador acreditado.

## 8. Modelo de dominio

### 8.1 Agregados

- `CapabilityDefinition`: `id`, `version`, `discipline`, `description`, `status`, `aliases`, `parentRefs`, `manifestSha256`. La jerarquía es informativa; sin herencia implícita.
- `SpecialistProfile`: `id`, `version`, `displayName`, `discipline`, `specializations[]`, `competencies[]`, `roles[]`, `jurisdictions[]`, `methodologyRefs[]`, `outputContractRefs[]`, `requiredTools[]`, `governanceRef`, `maxConcurrent`, `costUnits`, `status`, `manifestSha256`.
- `ProfileProposal`: `proposalId`, `source` (`HUMAN`/`AI`), `proposedManifest`, `proposerId`, `createdAt`, `validationResult`, `status`.
- `ProfileApproval`: `approvalId`, `proposalId`, `profileRef`, `manifestSha256`, `actorId`, `decision`, `reason`, `policyVersion`, `decidedAt`.
- `RegistryRevision`: secuencia durable para invalidar cachés y reproducir cambios.
- `SelectionDecision`: `requestId`, `registryRevision`, `requiredCapabilities`, `candidateCount`, `selectedProfileRef?`, `reasonCodes`, `costUnits?`, `createdAt`. El log no almacena secretos.

### 8.2 Tipos de valor e invariantes

- Identificadores estables normalizados, longitud máxima y caracteres permitidos; versiones semánticas explícitas.
- `ProfileRef = (id, version)`; clave única.
- `CompetencyRef = (capabilityId, capabilityVersion, requiredProficiency)`; la competencia debe existir y estar publicada.
- `JurisdictionRef`: código validado, sin inferir licencia profesional.
- `ToolPermissionRef`: solo solicitud de compatibilidad, nunca concesión de permiso.
- `manifestSha256`: SHA-256 de representación canónica definida por esquema, sin campos volátiles. Especificar serialización determinista y fixtures de hash.
- Una versión publicada es inmutable; estados y decisiones se guardan en registros separados para no cambiar el digest del manifiesto.
- Prohibir ciclos en relaciones de capacidades y referencias inexistentes; no aceptar perfiles sin competencias o costos negativos.
- `maxConcurrent` es límite declarado; S1 debe comprobar que la semántica efectiva de concurrencia no se anuncia como garantizada sin un mecanismo de reserva durable.

### 8.3 Ejemplo de manifiesto

```yaml
schemaVersion: "2.0"
kind: SpecialistProfile
id: legal.contracts.cl
version: 1.0.0
displayName: Analista de contratos comerciales de Chile
discipline: legal
specializations: [commercial-contracts, technology-contracts]
competencies:
  - capabilityId: legal.contracts.clause-analysis
    capabilityVersion: 1.0.0
    requiredProficiency: ADVANCED
roles: [ANALYST]
jurisdictions: [CL]
methodologyRefs: [legal.contract-review@1.0.0]
outputContractRefs: [legal.contract-risk-report@1.0.0]
requiredTools: []
governanceRef: legal-high-impact@1.0.0
maxConcurrent: 1
costUnits: 3
```

El manifiesto es ejemplo de contrato futuro; las metodologías y capacidades referenciadas deben existir para poder activarlo. No publicar el ejemplo como perfil operativo sin sus dependencias y evaluaciones.

## 9. Contratos de API y Event Bus

**Consulta interna:** `aies.specialists.select.v1` preservado. `aies.specialists.select.v2` incorporará `requestId`, `workflowId`, `taskId`, `requiredCompetencies[]`, `jurisdiction?`, `policyRef?`, `maxCostUnits`, `permittedTools[]` y devolverá `selected`, `profileRef?`, `costUnits?`, `registryRevision`, `reasonCodes[]`. `selected=false` es resultado de negocio; timeout, integridad corrupta y falta de autorización son errores distintos y no se convierten en selección vacía silenciosamente.

**Administración:** definir endpoints versionados de operador, sin reutilizar permisos implícitos: crear propuesta, validar, inspeccionar, aprobar, activar, deprecar, revocar y consultar. Todos los mutadores requieren autenticación/autorización explícita, idempotency key y auditoría. No aceptar la identidad del aprobador desde el cuerpo sin validación de sesión/token. Usar `@Blocking` o workers para JDBC/archivos; Event Bus para coordinación interna.

**Error envelope:** `code`, `message` seguro, `correlationId`, `retryable`, `details` no sensibles. Estados de negocio no se confunden con HTTP 500. Nunca registrar tokens, prompts completos o documentos sensibles en logs de error.

## 10. Persistencia SQLite y migración

Tablas propuestas:

```text
capability_definitions(capability_id, version, manifest_json, sha256, status, created_at)
specialist_profiles(profile_id, version, manifest_json, sha256, created_at)
specialist_profile_states(profile_id, version, status, revision, updated_at)
profile_proposals(proposal_id, source, proposer_id, manifest_json, status, created_at)
profile_approvals(approval_id, proposal_id, profile_id, version, sha256, actor_id, decision, reason, created_at)
registry_events(event_id, aggregate_id, event_type, payload_sha256, created_at)
registry_outbox(event_id, destination, payload_json, state, attempt_count, created_at)
selection_decisions(request_id, registry_revision, selected_profile_id, selected_version, reason_codes_json, created_at)
```

Claves foráneas, `UNIQUE(profile_id,version)`, checks de estado y CAS por `revision`. No modificar SQLite manualmente en operación. Migraciones versionadas y transaccionales, respaldos verificables, PRAGMA adecuados, y pruebas de reinicio. Si hay múltiples procesos, no asumir coordinación distribuida a partir de un archivo SQLite compartido sin pruebas específicas.

**Migración por fases:**
1. Congelar fixtures de selección v1 y casos de `BLOCKED_SPECIALIST`.
2. Crear esquema S1 sin cambiar rutas activas.
3. Importar idempotentemente los cuatro perfiles legados con sus IDs, capacidades, herramientas, costos y concurrencia exactos. Verificar hash y recuento.
4. Ejecutar comparaciones v1 vs nuevo registro en pruebas y modo sombra sin efectos.
5. Habilitar registro por flag solo cuando el catálogo esté íntegro; fallo cerrado si la DB falla.
6. Revertir el flag sin eliminar perfiles ni decisiones históricas. No reescribir workflows persistidos ni alterar asignaciones anteriores.

Los perfiles semilla activos son una migración controlada de configuración preexistente, no una excepción abierta para activar propuestas de IA.

## 11. Seguridad y amenazas

- **Escalamiento por prompt:** texto propuesto por IA se trata como dato no confiable; nunca ejecuta instrucciones ni cambia permisos.
- **Suplantación de aprobador:** aprobación ligada a identidad autenticada, autorización vigente, digest exacto y evento auditable.
- **TOCTOU:** congelar perfil y versión en asignación; revalidar revocación y permisos al ejecutar acciones materiales.
- **Manipulación de manifiestos:** canonicalización, SHA-256 y comparación antes de publicación/lectura crítica.
- **Confused deputy:** separación entre capacidades declaradas, herramientas solicitadas y grants efectivos.
- **Filtración de información:** límites de tamaño, campos sensibles protegidos, errores y logs redactados, jurisdicción/política de datos cuando aplique.
- **DoS y saturación:** límites de catálogo, profundidad de referencias, concurrencia, tiempos, consultas y tamaño de manifiestos.
- **Ciclos de aprobación:** ningún perfil/IA puede aprobar su propuesta; para alto riesgo, separación de funciones y revisión humana.
- **Replay:** claves de idempotencia, versión esperada, CAS, registros append-only y rechazo de decisiones antiguas.
- **Dependencia de proveedor:** S1 no liga perfiles a Ollama; el modelo será asignado por adaptador en S2.

## 12. Pruebas obligatorias

**Unitarias:** esquema y canonicalización, IDs, semver, duplicados, ciclos, competencias ausentes, transiciones de estado, inmutabilidad, elegibilidad, desempate determinista, presupuesto y compatibilidad de herramientas.

**Integración SQLite:** migración desde DB vacía y con datos; importación idempotente; reinicio; integridad; CAS concurrente; rollback por fallo; revocación; lectura consistente y reconstrucción de eventos.

**Event Bus/Quarkus:** selección v1 idéntica para fixtures legados; selección v2; consumidor deshabilitado; timeout; fallo DB; eventos duplicados; ninguna operación bloqueante en event loop.

**Seguridad:** aprobación anónima/ inválida, autoaprobación por IA, cambio de digest entre validación y aprobación, token filtrado, perfiles revocados, herramientas no permitidas, intentos de ampliar competencias por alias, manipulación de payload, replay y revocación durante ejecución.

**Regresión:** `./gradlew clean test --no-daemon --console=plain`, pruebas de planificación, handoff, recuperación y aceptación existentes, `git diff --check`. Ningún resultado de pruebas se declara exitoso hasta ejecutarlo en la rama real.

## 13. Criterios de aceptación S0

- **S0-AC01:** ADR-001 a ADR-005 revisados y aprobados.
- **S0-AC02:** esquemas JSON/YAML y contratos Java documentados, con versionado y ejemplos válidos e inválidos.
- **S0-AC03:** catálogo semilla refleja exactamente los cuatro especialistas de `main`.
- **S0-AC04:** matriz de compatibilidad v1/v2, migración y rollback documentados.
- **S0-AC05:** threat model, clasificación de riesgo, controles de aprobación y permisos definidos.
- **S0-AC06:** plan de pruebas deterministas y negativos reproducibles aprobado.
- **S0-AC07:** no se modifican rutas productivas ni se declara F4.3 cerrado.

## 14. Criterios de aceptación S1

- **S1-AC01:** registro durable de perfiles y competencias con versiones y SHA-256.
- **S1-AC02:** cuatro perfiles legados migrados sin diferencia en selección v1 para casos compatibles.
- **S1-AC03:** propuesta de IA solo crea `DRAFT`; jamás activa un perfil.
- **S1-AC04:** activación sin aprobación válida es rechazada y auditada.
- **S1-AC05:** publicación crea versión inmutable; intento de modificarla falla.
- **S1-AC06:** capacidad inexistente devuelve resultado explícito de no elegibilidad, sin asignación inventada.
- **S1-AC07:** perfil `DEPRECATED` o `REVOKED` no recibe asignaciones nuevas.
- **S1-AC08:** aprobación duplicada/concurrente no produce dos publicaciones.
- **S1-AC09:** reinicio de Quarkus conserva perfiles, estados, decisiones y hashes.
- **S1-AC10:** caída/corrupción del registro con flag activo falla cerrada, sin fallback inseguro.
- **S1-AC11:** operaciones administrativas sin autorización son rechazadas; no se registran secretos.
- **S1-AC12:** suites de regresión y pruebas negativas ejecutadas y documentadas con resultados reales.
- **S1-AC13:** migración reversible mediante flag sin borrar historial ni cambiar workflows existentes.
- **S1-AC14:** `documentation.technical.authoring` solo es asignable si existe un perfil activo que la declara explícitamente.

## 15. Riesgos y decisiones pendientes para revisión de S0

1. Determinar la estrategia de canonicalización (p. ej. JSON canónico formalmente especificado) y sus fixtures.
2. Definir el mecanismo de autenticación/autorización de operador reutilizando el existente sin expandir permisos implícitos.
3. Determinar si `RegistryRevision` es global o por agregado; documentar invalidación de caché.
4. Decidir si S1 registra decisiones de selección de solo lectura o todas las solicitudes, con política de retención y privacidad.
5. Determinar comportamiento de intentos en vuelo ante revocación; no afirmar cancelación instantánea de efectos externos.
6. Verificar rama de trabajo frente a `main`, rutas existentes, configuración real y nombres definitivos de módulos antes de codificar.
7. No confundir perfil legal/contable con profesional licenciado; definir escalamiento humano para decisiones reguladas.

## 16. Instrucciones de ejecución para el futuro implementador

1. Crear primero ADR, esquemas, fixtures y tests de contrato de S0.
2. Solicitar revisión/aprobación de S0 antes de cambios de runtime.
3. Implementar S1 en incrementos pequeños con feature flag deshabilitado por defecto.
4. Preservar los endpoints/eventos existentes, no sobreescribir tests ni scripts, no modificar estados SQLite manualmente.
5. Ejecutar tests reales y registrar evidencia, fallos y riesgos abiertos.
6. Actualizar exclusivamente el roadmap canónico `docs/NEXT_HITOS_CONTEXT.md` según las reglas del repositorio.
7. No declarar completado S1 ni F4.3 sin criterios y evidencias correspondientes.
