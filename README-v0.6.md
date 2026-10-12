# AI Engineering Station v0.6 — Git worktree review workflow

**Entrega incremental**, no reemplazo del repositorio. Base inspeccionada: `feat/tool-gateway-v05`, SHA `3015ba37354572b8372f11e71192914c2fc54707`. No se ejecutó Gradle contra el repositorio completo en el entorno de preparación.

## Instalar sin sobrescribir tu trabajo

```bash
cd ~/git/ai-engineering-station
git switch -c feat/worktree-v06
unzip -o ~/Descargas/aies-v0.6.zip -d .
python3 -m unittest discover -s tests -p 'test_worktree_v06.py' -v
```

Si ya existe la rama, elige otro nombre. Ejecuta desde la raíz del repositorio. El ZIP contiene rutas relativas. Revisa `git diff` antes de confirmar.

## Prueba funcional con cambios aislados

Primero confirma/guarda tus cambios locales: `git status --short` debe estar vacío. La herramienta no trabaja sobre un árbol sucio.

```bash
export AIES_WORKSPACE="$PWD"
python3 tools/aies_worktree.py init --run demo01
python3 tools/aies_worktree.py propose --run demo01 --path examples/Greeting.java --content-file examples/v06-proposal.java
python3 tools/aies_worktree.py review --run demo01
python3 tools/aies_worktree.py approve --run demo01
```

Copia el `sha256` exacto mostrado en el paso `approve` y, **solo tras revisar el diff**, ejecuta:

```bash
python3 tools/aies_worktree.py apply --run demo01 --sha256 PEGAR_SHA256
```

Se crea un commit **solo en la rama aislada** `aies/v06-demo01`. No se hace merge, cherry-pick ni push. Inspecciona:

```bash
git log -1 --oneline aies/v06-demo01
git diff HEAD..aies/v06-demo01 --stat
git diff HEAD..aies/v06-demo01
```

Si decides incorporar el cambio, hazlo manualmente tras validarlo: `git cherry-pick aies/v06-demo01`. Para desecharlo, usa `git worktree list` y luego `git worktree remove <ruta>`; la rama permanece hasta que la elimines explícitamente.

## Pruebas

```bash
bash scripts/v06-smoke.sh
```

La suite v0.6 comprueba restricciones de rutas; las pruebas Gradle verifican regresiones de la base. No ejecuta código propuesto automáticamente. **No constituye aislamiento OS, evaluación por IA ni sandbox seguro para código malicioso.**

## Alcance deliberadamente pendiente

El modelo Qwen aún no produce parches automáticamente en este paquete. Para conectar propuestas del modelo, se necesita salida estructurada con validación de contenido, revisión de diff, ejecución de tests aislada, control de presupuestos y aprobación humana por paso. Ver `docs/CATASTRO-v0.6.md`.
