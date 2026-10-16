#!/usr/bin/env python3
"""AIES SQLite estate audit. Read-only by default; never rename an active store.

Uses SQLite online backup API for consistent copies. The optional quarantine command
moves only EMPTY, UNKNOWN, unreferenced databases after an explicit stop assertion.
No production DB is ever deleted, merged, VACUUMed or rewritten by this utility.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import time

# Confirmed runtime consumers in the Java/Python source tree (not proof of live usage).
OWNERS = {
    'tasks.db': 'TaskRuntime / SqliteTaskRepository',
    'communication.sqlite': 'CommunicationStore / CommunicationEventBus',
    'execution-handoff.sqlite': 'ExecutionHandoffStore / ExecutionWorkerEngine',
    'planning.sqlite': 'PlanningStore / PlanningEventBus',
    'science.sqlite': 'ScientificWorkflowStore / ResearchEvidenceStore',
    'project-memory-v017.sqlite': 'SqliteProjectMemory / MemoryResource',
    'verification-evidence.sqlite': 'VerificationEvidenceRepository',
    'workspace-changes.sqlite': 'SqliteWorkspaceChangeRepository',
    'specialists.sqlite': 'SpecialistRegistryStore / GovernedSpecialistSelector',
    'audit.sqlite': 'scripts/aies_p0.py',
}
SUFFIXES = ('.sqlite', '.db')


def discover(root):
    roots = [root / 'data', root / 'apps/control-plane/data']
    files = set()
    for directory in roots:
        if directory.exists():
            for path in directory.rglob('*'):
                if path.name.endswith(SUFFIXES) and (path.is_file() or path.is_symlink()):
                    files.add(path)
    return sorted(files)


def inspect(path):
    entry = {'path': str(path), 'name': path.name, 'owner': OWNERS.get(path.name),
             'classification': 'referenced' if path.name in OWNERS else 'unknown',
             'size_bytes': path.stat().st_size, 'tables': {}, 'status': 'uninspected'}
    if path.is_symlink():
        entry.update(status='symlink_manual_review')
        return entry
    try:
        with path.open('rb') as stream:
            header = stream.read(16)
        if header != b'SQLite format 3\x00':
            entry.update(status='invalid_header')
            return entry
        # URI mode=ro: no schema migrations, no file creation, no implicit writes.
        with sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True, timeout=2) as db:
            db.execute('PRAGMA query_only=ON')
            entry['quick_check'] = [x[0] for x in db.execute('PRAGMA quick_check(1)')]
            entry['foreign_key_violations'] = len(db.execute('PRAGMA foreign_key_check').fetchall())
            entry['user_version'] = db.execute('PRAGMA user_version').fetchone()[0]
            for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
                quoted = '"' + name.replace('"', '""') + '"'
                entry['tables'][name] = db.execute(f'SELECT COUNT(*) FROM {quoted}').fetchone()[0]
            entry['status'] = 'ok' if entry['quick_check'] == ['ok'] and entry['foreign_key_violations'] == 0 else 'review_required'
    except (sqlite3.Error, OSError) as e:
        entry.update(status='read_error', error=str(e))
    return entry


def online_backup(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise FileExistsError(f'Backup already exists: {target}')
    try:
        with sqlite3.connect(source.resolve().as_uri() + '?mode=ro', uri=True, timeout=5) as src:
            with sqlite3.connect(target) as dst:
                src.backup(dst)
                if dst.execute('PRAGMA quick_check(1)').fetchone()[0] != 'ok':
                    raise RuntimeError('Backup quick_check failed')
    except Exception:
        target.unlink(missing_ok=True)
        raise


def has_open_handles(path):
    # Linux-only conservative check: permission errors mean we cannot prove safety.
    proc = Path('/proc')
    if not proc.is_dir():
        return True
    resolved = path.resolve()
    for process in proc.iterdir():
        if not process.name.isdecimal():
            continue
        fd = process / 'fd'
        try:
            handles = list(fd.iterdir())
        except (OSError, PermissionError):
            return True
        for handle in handles:
            try:
                if handle.resolve() == resolved:
                    return True
            except (OSError, PermissionError):
                return True
    return False


def run(root, backup_dir=None, quarantine=False, stopped=False):
    root = root.resolve()
    if not (root / 'settings.gradle.kts').is_file():
        raise ValueError('Expected AIES repository root (settings.gradle.kts missing)')
    if quarantine and (backup_dir is None or not stopped):
        raise ValueError('Quarantine requires --backup-dir and --confirm-stopped')
    report = {'schemaVersion': 1, 'repository': str(root), 'entries': [],
              'note': 'Referenced means present in source, not proven live. No automatic deletion of known stores.'}
    backup_dir = backup_dir.resolve() if backup_dir else None
    if backup_dir and (backup_dir == root or root in backup_dir.parents):
        raise ValueError('Backup directory must be outside the repository')
    for path in discover(root):
        entry = inspect(path)
        if backup_dir and entry['status'] == 'ok':
            relative = path.relative_to(root)
            dest = backup_dir / 'sqlite-backups' / relative
            online_backup(path, dest)
            entry['backup'] = str(dest)
            entry['backup_sha256'] = hashlib.sha256(dest.read_bytes()).hexdigest()
        if quarantine and entry['classification'] == 'unknown' and entry['status'] == 'ok' and not any(entry['tables'].values()):
            # A zero-row database may still carry schema: quarantine, never delete.
            if any(Path(str(path) + suffix).exists() for suffix in ('-wal', '-shm', '-journal')):
                entry['quarantine'] = 'blocked_sidecars'
            elif has_open_handles(path):
                entry['quarantine'] = 'blocked_open_or_unverifiable_handles'
            else:
                dest = backup_dir / 'quarantine' / path.relative_to(root)
                dest.parent.mkdir(parents=True, exist_ok=True)
                if dest.exists():
                    entry['quarantine'] = 'blocked_existing_destination'
                else:
                    os.replace(path, dest)
                    entry['quarantine'] = str(dest)
        report['entries'].append(entry)
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=Path.cwd())
    p.add_argument('--backup-dir', type=Path, help='Online consistent backup outside repo; never overwrite')
    p.add_argument('--quarantine-empty-unknown', action='store_true', help='Opt-in; never deletes')
    p.add_argument('--confirm-stopped', action='store_true', help='Operator attests all AIES writers are stopped')
    args = p.parse_args()
    try:
        report = run(args.root, args.backup_dir, args.quarantine_empty_unknown, args.confirm_stopped)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        if any(e['status'] != 'ok' for e in report['entries']):
            return 2
        return 0
    except (ValueError, OSError, sqlite3.Error, RuntimeError) as exc:
        print(f'AIES SQLite audit failed: {exc}', file=sys.stderr)
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
