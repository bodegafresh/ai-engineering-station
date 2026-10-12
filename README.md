# AIES v0.4 — Herramientas locales de navegador y diagramas

Parche **aditivo**: no sustituye clases Java ni modifica Gradle/Quarkus. Se puede extraer sobre el repositorio existente. No requiere cuentas ni servicios pagados.

## Instalar en Fedora

```bash
cd ~/git/ai-engineering-station
unzip -o ~/Descargas/ai-engineering-station-v0.4-tools.zip -d .
bash scripts/setup-fedora.sh
```

**Nota:** el instalador de Fedora puede requerir ajustar paquetes a tu versión; si `plantuml` no está disponible por DNF, instala PlantUML desde su distribución oficial o usa el paquete disponible en tus repositorios. El navegador Chromium de Playwright puede requerir bibliotecas adicionales. El script instala dependencias Python en `.aies/venv`, no en el Python del sistema.

## Primera prueba: diagrama

```bash
AIES_WORKSPACE="$PWD" .aies/venv/bin/python tools/aies_tools.py diagram \
  --source examples/architecture.puml --output .aies/artifacts/architecture.svg
```

Abre `.aies/artifacts/architecture.svg` con tu navegador o visor de imágenes.

## Segunda prueba: navegador y UX

Arranca una aplicación web de prueba en localhost (por ejemplo `python3 -m http.server 8765` en una carpeta con HTML). En otra terminal:

```bash
AIES_WORKSPACE="$PWD" .aies/venv/bin/python tools/aies_tools.py browser \
  --url http://127.0.0.1:8765 --output .aies/artifacts/home.png
```

Devuelve JSON con título, encabezados, botones, imágenes sin atributo alt, desbordamiento horizontal y ruta de la captura. Es una **inspección automatizada básica**, no una evaluación visual completa ni una auditoría de accesibilidad WCAG.

## Pruebas de política

```bash
AIES_WORKSPACE="$PWD" python3 -m unittest discover -s tests -p 'test_policy.py' -v
```

## Limitaciones importantes

- **No existe todavía un ciclo autónomo de llamadas a herramientas desde Qwen**. En v0.4 las herramientas se invocan manualmente. El contrato de integración propuesto para Quarkus está en `docs/NEXT-QUARKUS-INTEGRATION.md`.
- El navegador solo permite `localhost`, `127.0.0.1` y `::1`. Bloquea peticiones externas, pero **no es un sandbox de seguridad fuerte** frente a páginas maliciosas, procesos locales hostiles o JavaScript que acceda a servicios locales. Úsalo con aplicaciones propias y sin datos sensibles.
- PlantUML se limita a archivos `.puml` locales, y bloquea varias directivas; **no constituye aislamiento completo** para diagramas no confiables. Se recomienda contenedor sin red y permisos limitados antes de aceptar contenido generado automáticamente.
- Las capturas se guardan en `.aies/artifacts/`. No se realiza análisis visual con un modelo multimodal todavía.
- No se genera arte ilustrativo con un modelo de difusión: PlantUML genera diagramas, no imágenes artísticas.
- Evita ejecutar Playwright sobre la misma sesión que contiene credenciales o perfiles personales.

## Git

Agrega al `.gitignore` del proyecto:

```
.aies/venv/
.aies/artifacts/
```
