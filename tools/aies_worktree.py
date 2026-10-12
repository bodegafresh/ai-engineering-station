#!/usr/bin/env python3
"""AIES v0.6: isolated Git worktree, constrained file proposal, explicit apply.
This is NOT an OS sandbox. Use trusted repositories and inspect all proposed code.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(os.environ.get('AIES_WORKSPACE', '.')).resolve()
STATE = ROOT / '.aies' / 'v06'
ALLOWED_EXT = {'.java', '.kt', '.kts', '.py', '.md', '.json', '.yaml', '.yml', '.properties', '.toml', '.ts', '.tsx', '.js', '.css', '.html'}
DENIED_PARTS = {'.git', '.env', '.ssh', '.aws', '.gradle', 'node_modules', 'build', 'target', '.aies'}
MAX_BYTES = 100_000

def git(*args, cwd=ROOT, check=True):
    return subprocess.run(['git', *args], cwd=cwd, text=True, capture_output=True, check=check)

def require_repo():
    if git('rev-parse', '--show-toplevel').stdout.strip() != str(ROOT):
        raise ValueError('AIES_WORKSPACE must be the Git repository root')

def safe_path(name):
    if not isinstance(name, str) or not name or '\\' in name or name.startswith('/'):
        raise ValueError('Invalid relative path')
    p = Path(name)
    if any(x in DENIED_PARTS or x == '..' for x in p.parts) or p.suffix.lower() not in ALLOWED_EXT:
        raise ValueError('Path denied by policy')
    return p

def state_file(run):
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,50}', run):
        raise ValueError('Invalid run id')
    return STATE / (run + '.json')

def read(run):
    return json.loads(state_file(run).read_text())

def write(run, data):
    STATE.mkdir(parents=True, exist_ok=True)
    state_file(run).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')

def init(args):
    require_repo()
    if git('status', '--porcelain').stdout.strip():
        raise ValueError('Main workspace must be clean before creating a worktree')
    sf = state_file(args.run)
    if sf.exists(): raise ValueError('Run already exists')
    wt = (STATE / 'worktrees' / args.run).resolve()
    wt.parent.mkdir(parents=True, exist_ok=True)
    branch = 'aies/v06-' + args.run
    git('worktree', 'add', '-b', branch, str(wt), 'HEAD')
    write(args.run, {'run':args.run,'worktree':str(wt),'branch':branch,'base':git('rev-parse','HEAD').stdout.strip(),'status':'CREATED','files':[]})
    print(json.dumps({'ok':True,'worktree':str(wt),'branch':branch}))

def propose(args):
    d = read(args.run)
    if d['status'] not in ('CREATED','PROPOSED'): raise ValueError('Run is not editable')
    rel = safe_path(args.path)
    wt = Path(d['worktree']).resolve()
    target = wt / rel
    if target.is_symlink() or any(p.is_symlink() for p in [target, *target.parents] if p != wt and wt in p.parents):
        raise ValueError('Symlinks denied')
    if not target.resolve().is_relative_to(wt): raise ValueError('Escaped worktree')
    content = Path(args.content_file).read_bytes()
    if len(content) > MAX_BYTES or b'\0' in content: raise ValueError('Content too large or binary')
    content.decode('utf-8')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(content)
    d['files'] = sorted(set(d['files'] + [str(rel)]))
    d['status'] = 'PROPOSED'
    write(args.run,d)
    print(json.dumps({'ok':True,'status':d['status'],'path':str(rel)}))

def review(args):
    d = read(args.run)
    wt = Path(d['worktree'])
    # Include untracked proposals without adding them to the index.
    tracked = git('diff','--no-ext-diff','--',*d['files'],cwd=wt).stdout
    untracked = []
    for rel in d['files']:
        if git('ls-files','--error-unmatch','--',rel,cwd=wt,check=False).returncode:
            p = wt / rel
            untracked.append(f'NEW FILE: {rel}\n' + p.read_text())
    print(tracked + '\n'.join(untracked))

def approve(args):
    d = read(args.run)
    if d['status'] != 'PROPOSED' or not d['files']: raise ValueError('Nothing to approve')
    wt = Path(d['worktree'])
    git('add','--',*d['files'],cwd=wt)
    diff = git('diff','--cached','--binary',cwd=wt).stdout
    if not diff: raise ValueError('Empty diff')
    digest = hashlib.sha256(diff.encode()).hexdigest()
    d['digest']=digest;d['status']='AWAITING_APPROVAL';write(args.run,d)
    print(json.dumps({'status':d['status'],'sha256':digest,'files':d['files']}))

def apply(args):
    d=read(args.run)
    if d['status']!='AWAITING_APPROVAL' or d.get('digest')!=args.sha256: raise ValueError('Approval digest mismatch')
    wt=Path(d['worktree'])
    diff=git('diff','--cached','--binary',cwd=wt).stdout
    if hashlib.sha256(diff.encode()).hexdigest()!=args.sha256: raise ValueError('Diff changed after approval')
    if git('rev-parse','HEAD').stdout.strip()!=d['base']: raise ValueError('Base HEAD changed; rebase/review again')
    if git('status','--porcelain').stdout.strip(): raise ValueError('Main workspace not clean')
    # Commit only in the isolated branch. Never modify the user's main worktree.
    git('-c','user.name=AIES Local','-c','user.email=aies@localhost','commit','-m','aies: approved proposal '+args.run,cwd=wt)
    d['status']='COMMITTED_IN_WORKTREE';d['commit']=git('rev-parse','HEAD',cwd=wt).stdout.strip();write(args.run,d)
    print(json.dumps({'ok':True,'status':d['status'],'commit':d['commit'],'branch':d['branch'],'merge_performed':False}))

def main():
    p=argparse.ArgumentParser(description='AIES v0.6 controlled Git worktrees')
    sub=p.add_subparsers(dest='cmd',required=True)
    for cmd in ('init','review','approve','apply'):
        q=sub.add_parser(cmd);q.add_argument('--run',required=True)
        if cmd=='apply':q.add_argument('--sha256',required=True)
    q=sub.add_parser('propose');q.add_argument('--run',required=True);q.add_argument('--path',required=True);q.add_argument('--content-file',required=True)
    a=p.parse_args()
    try:
        {'init':init,'propose':propose,'review':review,'approve':approve,'apply':apply}[a.cmd](a)
    except (ValueError,OSError,subprocess.CalledProcessError) as e:
        print(json.dumps({'ok':False,'error':str(e)}),file=sys.stderr);sys.exit(1)
if __name__=='__main__':main()
