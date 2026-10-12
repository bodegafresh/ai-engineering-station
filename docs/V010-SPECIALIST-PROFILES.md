# AI Engineering Station v0.10 — Specialist knowledge profiles

## Alcance
Overlay aditivo sobre main con v0.8–v0.9 y Velocity ya integrados. No sustituye clases existentes, no altera rutas REST, no modifica base de datos, ni habilita ejecución automática.

## Arquitectura
- `prompts/catalog.json`: allow-list y capacidades del especialista (existente).
- `prompts/specialists/*.vm`: instrucciones dinámicas (existente).
- `specialists/profiles/*.md`: conocimiento normativo versionado (nuevo).
- `SpecialistProfileRegistry`: lectura segura desde classpath (nuevo).
- `SpecialistProfileRegistryTest`: prueba de correspondencia catálogo/perfiles (nuevo).

## Integración posterior (NO incluida)
En el coordinador de agentes, cargar `registry.profile(specialistId)` y suministrarlo como contexto de sistema controlado, con presupuesto de tokens y versionado. No interpolar texto no confiable como instrucciones de sistema. El router debe proponer especialistas y el revisor de factibilidad aprobar el plan antes de conceder permisos. Ningún perfil Markdown otorga herramientas. Las reglas de ejecución deben implementarse en código y políticas, no confiar en el prompt.

## Validación
```bash
python3 scripts/verify-specialist-profiles.py
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## Instalación
```bash
unzip -o ~/Descargas/aies-v010-specialist-profiles.zip -d /tmp
bash /tmp/aies-v010-specialist-profiles/scripts/install-v010.sh "$PWD"
```

El instalador no sobrescribe archivos existentes. Trabajar desde una rama creada desde main actualizado.
