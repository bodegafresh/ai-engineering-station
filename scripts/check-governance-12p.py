#!/usr/bin/env python3
"""Static consistency gate for the 12p governance review; no production changes."""
from pathlib import Path
import sys
root=Path(__file__).resolve().parents[1]
roadmap=root/'docs/NEXT_HITOS_CONTEXT.md'
adr=root/'docs/adr/H02-D2C2f-12p-governance-certification.md'
matrix=root/'docs/governance/H02-D2C2f-assurance-matrix.md'
service=root/'apps/control-plane/src/main/java/dev/aies/control/execution/WorkspaceDocumentationService.java'
policy=root/'apps/control-plane/src/main/java/dev/aies/control/execution/GovernedToolResourcePolicyStore.java'
errors=[]
for p in (roadmap,adr,matrix,service,policy):
    if not p.is_file(): errors.append(f'missing {p.relative_to(root)}')
if not errors:
    for path,terms in {
        roadmap:('## H02-D2C2f.12p','H02-D2C2f.12q','NO-GO'),
        adr:('NO-GO','BEGIN IMMEDIATE','power','revocation'),
        matrix:('NO-GO','GovernedDocumentationProcessCrashChild','Not guaranteed'),
        service:('requirePublicationAdmission','AFTER_FILE_JOURNAL','AFTER_ATOMIC_MOVE','AFTER_ARTIFACT_APPEND'),
        policy:('admitPublication','governed_publication_admissions')
    }.items():
        data=path.read_text()
        for term in terms:
            if term not in data: errors.append(f'{path.relative_to(root)}: missing {term}')
if errors:
    print('\n'.join('FAIL '+e for e in errors),file=sys.stderr)
    sys.exit(1)
print('PASS: 12p review artifacts and key implementation anchors present (static gate only)')
