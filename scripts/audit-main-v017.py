#!/usr/bin/env python3
"""Static integration audit: existence does not imply runtime wiring."""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else '.')
java=list((root/'apps/control-plane/src/main/java').rglob('*.java'))
text={str(p.relative_to(root)):p.read_text() for p in java}
checks={
 'memory API reachable via JAX-RS': any('@Path("/api/v1/memory")' in s for s in text.values()),
 'persistent SQLite memory': any('class SqliteProjectMemory' in s for s in text.values()),
 'memory API calls persistent store': any('new SqliteProjectMemory(' in s and '@Path("/api/v1/memory")' in s for s in text.values()),
 'event bus coordinator present': any('class EventBusTeamCoordinator' in s for s in text.values()),
 'event bus coordinator referenced by runtime': any('new EventBusTeamCoordinator(' in s and 'class EventBusTeamCoordinator' not in s for s in text.values()),
 'model routing present': any('class ModelRouter' in s for s in text.values()),
 'model routing referenced by runtime': any('new ModelRouter(' in s and 'class ModelRouter' not in s for s in text.values()),
 'capability gateway present': any('class ToolGateway' in s for s in text.values()),
 'capability gateway referenced by runtime': any('new ToolGateway(' in s and 'class ToolGateway' not in s for s in text.values()),
}
for name,ok in checks.items():print(('PASS' if ok else 'GAP ')+': '+name)
print('NOTE: static checks only; use integration tests and live calls to establish runtime functionality.')
