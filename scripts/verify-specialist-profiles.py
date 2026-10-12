#!/usr/bin/env python3
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
catalog_path = root/'apps/control-plane/src/main/resources/prompts/catalog.json'
if not catalog_path.exists():
    raise SystemExit('ERROR: run after v0.8-v0.9 prompts have been merged')
ids = {x['id'] for x in json.loads(catalog_path.read_text())['specialists']}
profiles = root/'apps/control-plane/src/main/resources/specialists/profiles'
found = {p.stem for p in profiles.glob('*.md')}
assert found == ids, f'missing={ids-found}; unexpected={found-ids}'
for id in sorted(ids):
    s=(profiles/f'{id}.md').read_text()
    assert s.startswith(f'---\nid: {id}\n'), id
    assert all(h in s for h in ('## Misión','## Competencias y conocimientos obligatorios','## Procedimiento','## Contrato de salida','## Criterios de aceptación')), id
print(f'PASS: {len(ids)} specialist profiles validated')
