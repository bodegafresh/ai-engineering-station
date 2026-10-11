# ai-engineering-station v0.3 — reparación de pruebas SQLite

Este parche reemplaza **únicamente** `adapters/sqlite/src/test/java/dev/aies/sqlite/SqliteTaskRepositoryTest.java`.
No modifica código de producción ni bases de datos.

## Aplicación

```bash
cd ~/git/ai-engineering-station
unzip -o ~/Descargas/ai-engineering-station-v0.3-sqlite-tests-fix.zip -d .
./gradlew clean test
```

## Qué valida

- Persistencia al reabrir SQLite.
- Outbox: entrega y no repetición después de confirmar, y reintento cuando el publicador falla.
- Cancelación de pendientes.
- Claim con lease y límite de intentos.
- Recuperación de leases expirados, rechazo de claims antiguos y agotamiento de intentos.
- Persistencia de respuesta de modelo y protección contra finalización duplicada.

## Nota de diseño

La API actual `fail(Claim, reason)` marca FAILED directamente; no implementa reintento inmediato.
Las pruebas de reintentos usan `recoverExpired(maxAttempts)` y vencimiento de lease.
No se ha ejecutado Gradle en este entorno porque el ZIP contiene únicamente el archivo de pruebas y no el proyecto completo ni sus dependencias.
