# H01-C4 — Runbook SQLite: respaldo, verificación y recuperación

**Estado:** implementación preparada; certificación en Fedora pendiente.

## Garantías y límites

El script `scripts/aies-sqlite-recovery.py` utiliza la API oficial de backup SQLite, compatible con WAL. Nunca sobrescribe una ruta existente y la restauración crea exclusivamente una base nueva. Comprueba `PRAGMA integrity_check`, `PRAGMA foreign_key_check` y emite SHA-256 y tamaño como evidencia JSON. No sustituye la base activa ni publica eventos.

El control estructural no sustituye la reconciliación semántica H01-C3 ni las comprobaciones de continuidad de estados. Antes de cualquier puesta en servicio, ejecutarlas sobre la copia restaurada mediante los verificadores existentes. No hay recuperación automática ni garantía frente a pérdida física del host.

## Preparación

Se requiere Python 3 con módulo `sqlite3`, espacio libre suficiente, rutas explícitas y permisos restringidos. Los backups contienen información sensible: conservarlos fuera de Git, cifrados según política y con retención documentada. No copiar únicamente el archivo `.sqlite` mientras haya conexiones activas en WAL.

## Comandos

```bash
python3 scripts/aies-sqlite-recovery.py backup /ruta/activa/control.sqlite /ruta/segura/backup.sqlite
python3 scripts/aies-sqlite-recovery.py verify /ruta/segura/backup.sqlite
python3 scripts/aies-sqlite-recovery.py restore /ruta/segura/backup.sqlite /ruta/aislada/restore.sqlite
python3 scripts/aies-sqlite-recovery.py verify /ruta/aislada/restore.sqlite
```

## Incidente y recuperación

1. Suspender escrituras de la aplicación y registrar versión, hora, operador y síntoma.
2. Preservar la base original y sus archivos WAL/SHM, sin alterarlos; conservar evidencia.
3. Generar un backup consistente usando la API SQLite, si la base permite lectura. Si falla, detener el procedimiento y escalar; no aplicar reparaciones destructivas.
4. Verificar integridad, hash y tamaño del respaldo. Restaurar a una ruta nueva y volver a verificar.
5. Ejecutar H01-C3 y los verificadores de continuidad sobre la copia. Una inconsistencia impide el retorno a servicio.
6. Obtener autorización humana explícita para un cambio productivo, usando un procedimiento de despliegue distinto y reversible. No automatizar el reemplazo.
7. Mantener la evidencia y la base original durante el periodo de observación.

## Validación

```bash
python3 -m unittest discover -s tests/ops -p 'test_sqlite_recovery.py' -v
./gradlew clean test --no-daemon --console=plain
git diff --check
```

El test cubre copia/restauración, rechazo de overwrite, corrupción y backup WAL. Faltan aún certificación integral, prueba operacional del reconciliador sobre una restauración y ejercicio de recuperación ante incidente (H01-C5). Event Bus v1 y selector v2 no cambian; F4/G10-K sigue abierto.
