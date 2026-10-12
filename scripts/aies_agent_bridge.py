#!/usr/bin/env python3
"""Strict, offline, human-operated bridge: agent output -> existing P0 proposal.
No shell execution, no approval, no apply. Treat agent JSON as untrusted input.
"""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import pathlib
import re
import sys

MAX_DOCUMENT = 128 * 1024
MAX_CHANGES = 20
MAX_CONTENT = 32768
ID = re.compile(r'^[A-Za-z0-9_-]{1,64}$')
HASH = re.compile(r'^[a-f0-9]{64}$')


def load_p0():
    path = pathlib.Path(__file__).with_name('aies_p0.py')
    spec = importlib.util.spec_from_file_location('aies_p0', path)
    if spec is None or spec.loader is None:
        raise ValueError('P0 CLI missing')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate(raw, run, state_root):
    if not ID.fullmatch(run):
        raise ValueError('Invalid run ID')
    if len(raw) > MAX_DOCUMENT:
        raise ValueError('Agent document too large')
    try:
        document = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Agent output must be UTF-8 JSON') from exc
    if not isinstance(document, dict) or set(document) != {'schemaVersion', 'runId', 'changes', 'objective'}:
        raise ValueError('Unexpected document fields')
    if type(document['schemaVersion']) is not int or document['schemaVersion'] != 1:
        raise ValueError('Unsupported schema version')
    if document['runId'] != run:
        raise ValueError('Run mismatch')
    if not isinstance(document['objective'], str) or not 1 <= len(document['objective']) <= 2000:
        raise ValueError('Invalid objective')
    items = document['changes']
    if not isinstance(items, list) or not 1 <= len(items) <= MAX_CHANGES:
        raise ValueError('Invalid changes')
    p0 = load_p0()
    workspace = p0.workspace(state_root, run)
    changes = {}
    for item in items:
        if not isinstance(item, dict) or set(item) != {'path', 'beforeSha256', 'replacement'}:
            raise ValueError('Unexpected change fields')
        rel, before, replacement = item['path'], item['beforeSha256'], item['replacement']
        if not isinstance(rel, str) or rel in changes or not isinstance(before, str) or not HASH.fullmatch(before):
            raise ValueError('Invalid path, duplicate, or hash')
        if not isinstance(replacement, str) or len(replacement.encode('utf-8')) > MAX_CONTENT:
            raise ValueError('Invalid replacement')
        old = p0.read(workspace, rel)
        if hashlib.sha256(old).hexdigest() != before:
            raise ValueError('Stale agent proposal')
        if old == replacement.encode('utf-8'):
            raise ValueError('No-op change')
        changes[rel] = replacement
    return changes, document


def bridge(state_root, run, agent_file):
    # No network, subprocess, model or write capability except P0 prepare.
    raw = agent_file.read_bytes()
    changes, document = validate(raw, run, state_root)
    p0 = load_p0()
    # Capture P0 prepare output so the caller receives one JSON object.
    capture = io.StringIO()
    with contextlib.redirect_stdout(capture):
        p0.prepare(state_root, run, changes)
    result = json.loads(capture.getvalue())
    result['origin'] = 'untrusted-agent-proposal'
    result['objectiveSha256'] = hashlib.sha256(document['objective'].encode('utf-8')).hexdigest()
    result['agentDocumentSha256'] = hashlib.sha256(raw).hexdigest()
    # Metadata only: does not attest model identity or human review.
    conn = p0.db(state_root)
    try:
        p0.event(conn, 'agent-proposal-imported', run, {
            'id': result['id'], 'agentDocumentSha256': result['agentDocumentSha256'],
            'objectiveSha256': result['objectiveSha256'], 'files': sorted(changes)
        })
    finally:
        conn.close()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state-root', type=pathlib.Path, required=True)
    parser.add_argument('--run', required=True)
    parser.add_argument('--agent-json', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = args.state_root.expanduser().resolve(strict=True)
    try:
        result = bridge(root, args.run, args.agent_json)
    except (ValueError, OSError, RuntimeError, KeyError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main())
