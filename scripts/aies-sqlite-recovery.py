#!/usr/bin/env python3
"""H01-C4 offline-safe SQLite backup, verification and isolated restore.

Uses SQLite online backup API (WAL-aware); never overwrites an existing destination.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys


def fail(message):
    raise RuntimeError(message)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def verify(path):
    if not path.is_file() or path.is_symlink():
        fail('Source must be an existing regular, non-symlink SQLite file')
    uri = path.resolve().as_uri() + '?mode=ro'
    with sqlite3.connect(uri, uri=True, timeout=10) as conn:
        checks = [row[0] for row in conn.execute('PRAGMA integrity_check')]
        if checks != ['ok']:
            fail('SQLite integrity_check failed: ' + repr(checks[:5]))
        fk = conn.execute('PRAGMA foreign_key_check').fetchmany(5)
        if fk:
            fail('SQLite foreign_key_check failed: ' + repr(fk))
    return {'file': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path), 'integrity': 'ok', 'foreign_keys': 'ok'}


def backup(source, destination):
    if not source.is_file() or source.is_symlink():
        fail('Source must be an existing regular, non-symlink SQLite file')
    if destination.exists() or destination.is_symlink():
        fail('Destination already exists; refusing overwrite')
    if not destination.parent.is_dir():
        fail('Destination parent directory does not exist')
    # Exclusive creation closes a check/use race; SQLite will open this reserved file.
    fd = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(fd)
    try:
        with sqlite3.connect(source.resolve().as_uri() + '?mode=ro', uri=True, timeout=10) as original:
            with sqlite3.connect(str(destination), timeout=10) as copy:
                original.backup(copy, pages=128, sleep=0.05)
        result = verify(destination)
        return result
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['backup', 'verify', 'restore'])
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', nargs='?', type=Path)
    args = parser.parse_args()
    if args.operation == 'verify':
        if args.destination is not None:
            parser.error('verify takes only a source')
        result = verify(args.source)
    else:
        if args.destination is None:
            parser.error('backup and restore require a new destination')
        if args.source.resolve() == args.destination.resolve():
            fail('Source and destination must differ')
        # Restore is an isolated copy: never swap files or touch an active database.
        if args.operation == 'restore':
            verify(args.source)
        result = backup(args.source, args.destination)
    print(json.dumps({'operation': args.operation, **result}, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, sqlite3.Error, OSError) as exc:
        print('FAILED: ' + str(exc), file=sys.stderr)
        sys.exit(1)
