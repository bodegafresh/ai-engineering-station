#!/usr/bin/env python3
"""Read-only, deterministic static inventory. No claim of runtime reachability."""
import argparse
import json
import re
from pathlib import Path

TARGETS = {
    'eventbus-v1': 'apps/control-plane/src/main/java/dev/aies/control/specialists/SpecialistEventBus.java',
    'specialist-rest-v1': 'apps/control-plane/src/main/java/dev/aies/control/specialists/SpecialistResource.java',
    'legacy-team-planner': 'apps/control-plane/src/main/java/dev/aies/control/team/TeamPlanner.java',
    'prompt-capability-router': 'apps/control-plane/src/main/java/dev/aies/control/routing/CapabilityRouter.java',
    'governed-registry': 'apps/control-plane/src/main/java/dev/aies/control/specialists/SpecialistRegistryStore.java',
    'governance-service': 'apps/control-plane/src/main/java/dev/aies/control/specialists/SpecialistGovernanceService.java',
    'operator-gateway': 'apps/control-plane/src/main/java/dev/aies/control/specialists/SpecialistOperatorGateway.java',
    'governance-reconciler': 'apps/control-plane/src/main/java/dev/aies/control/specialists/SpecialistGovernanceReconciler.java',
    'model-router': 'apps/control-plane/src/main/java/dev/aies/control/models/ModelRouter.java',
}

def inventory(root):
    source_root = root / 'apps/control-plane/src/main/java'
    sources = sorted(source_root.rglob('*.java')) if source_root.exists() else []
    content = {str(p.relative_to(root)): p.read_text(encoding='utf-8') for p in sources}
    result = []
    for name, path in TARGETS.items():
        target = root / path
        class_name = target.stem
        references = []
        for candidate, body in content.items():
            if candidate == path:
                continue
            # Word-boundary reference only; static occurrence, NOT a runtime call graph.
            if re.search(r'\b' + re.escape(class_name) + r'\b', body):
                references.append(candidate)
        result.append({'id': name, 'path': path, 'exists': target.is_file(),
                       'java_reference_files': references,
                       'classification': 'static-reference-only; runtime wiring not proven'})
    config = root / 'apps/control-plane/src/main/resources/application.properties'
    flags = []
    if config.is_file():
        for line in config.read_text(encoding='utf-8').splitlines():
            if line.startswith('aies.') and '.enabled=' in line:
                key, value = line.split('=', 1)
                flags.append({'key': key, 'configured_default': value,
                              'note': 'runtime environment can override default'})
    return {'schemaVersion': 1, 'method': 'static source inventory; no runtime assertion',
            'components': result, 'feature_flags': sorted(flags, key=lambda x: x['key'])}

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--root', type=Path, default=Path.cwd())
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    root = args.root.resolve()
    if not (root / 'settings.gradle.kts').is_file():
        p.error('--root must point to AIES repository root (settings.gradle.kts)')
    report = json.dumps(inventory(root), indent=2, ensure_ascii=False) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding='utf-8')
    else:
        print(report, end='')
if __name__ == '__main__':
    main()
