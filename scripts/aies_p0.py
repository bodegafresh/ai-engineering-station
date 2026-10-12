#!/usr/bin/env python3
"""Local, opt-in P0 workflow. Human-operated CLI; never callable by models."""
import argparse, hashlib, json, os, pathlib, secrets, shutil, sqlite3, subprocess, sys, tempfile, time, difflib, stat
from datetime import datetime, timezone

MAX_FILES=20; MAX_BYTES=32768; TTL=900

def sha(b): return hashlib.sha256(b).hexdigest()
def now(): return datetime.now(timezone.utc).isoformat()
def db(root):
    d=root/'.aies-p0'; d.mkdir(mode=0o700,exist_ok=True)
    os.chmod(d,0o700)
    c=sqlite3.connect(d/'audit.sqlite', timeout=10, isolation_level=None)
    c.execute('PRAGMA journal_mode=WAL'); c.execute('PRAGMA busy_timeout=10000')
    c.execute('CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, at TEXT NOT NULL, action TEXT NOT NULL, run TEXT NOT NULL, detail TEXT NOT NULL)')
    c.execute('CREATE TABLE IF NOT EXISTS proposals (id TEXT PRIMARY KEY, run TEXT NOT NULL, state TEXT NOT NULL, expires REAL NOT NULL, digest TEXT NOT NULL, approval TEXT, base TEXT NOT NULL)')
    return c

def event(c,action,run,detail): c.execute('INSERT INTO events(at,action,run,detail) VALUES(?,?,?,?)',(now(),action,run,json.dumps(detail,sort_keys=True)))
def git(repo,*args):
    p=subprocess.run(['git','-C',str(repo),*args],text=True,capture_output=True,timeout=30)
    if p.returncode: raise RuntimeError('git '+ ' '.join(args)+': '+p.stderr[:600])
    return p.stdout.strip()
def check_name(v):
    if not v or len(v)>64 or not all(x.isascii() and (x.isalnum() or x in '-_') for x in v): raise ValueError('Invalid identifier')
def path_under(root, rel, must_exist=True):
    if not isinstance(rel,str) or len(rel)>240 or '\\' in rel or '\0' in rel: raise ValueError('Invalid path')
    p=pathlib.PurePosixPath(rel)
    if p.is_absolute() or len(p.parts)>8 or any(x in ('','.', '..') or x.startswith('.') for x in p.parts): raise ValueError('Unsafe path')
    if any(x.lower() in ('secrets','node_modules','build','target','dist') for x in p.parts): raise ValueError('Unsafe directory')
    if any(k in p.name.lower() for k in ('secret','password','credential','token','private','id_rsa')): raise ValueError('Sensitive filename')
    if not p.name.lower().endswith(('.java','.kt','.py','.md','.txt','.json','.yaml','.yml','.xml','.ts','.js','.go','.rs','.sql')): raise ValueError('Unsupported extension')
    target=root.joinpath(*p.parts)
    current=root
    if current.is_symlink(): raise ValueError('Symlink forbidden')
    for part in p.parts[:-1]:
        current=current/part
        if current.is_symlink(): raise ValueError('Symlink forbidden')
    if target.is_symlink(): raise ValueError('Symlink forbidden')
    if must_exist and not target.is_file(): raise ValueError('Missing regular file')
    if must_exist and not stat.S_ISREG(target.lstat().st_mode): raise ValueError('Not a regular file')
    if not target.resolve(strict=False).is_relative_to(root.resolve()): raise ValueError('Escape')
    return target

def read(root, rel):
    p=path_under(root,rel)
    if p.stat().st_size>MAX_BYTES: raise ValueError('File too large')
    flags=os.O_RDONLY | getattr(os,'O_NOFOLLOW',0) | getattr(os,'O_CLOEXEC',0)
    fd=os.open(p,flags)
    try:
        st=os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_size>MAX_BYTES: raise ValueError('Not a bounded regular file')
        with os.fdopen(fd,'rb',closefd=False) as f: b=f.read(MAX_BYTES+1)
        if os.stat(p,follow_symlinks=False).st_ino!=st.st_ino: raise ValueError('File changed while reading')
    finally: os.close(fd)
    if len(b)>MAX_BYTES or b'\0' in b: raise ValueError('Invalid file')
    return b

def workspace(root,run):
    check_name(run)
    w=root/'.aies-p0'/'worktrees'/run
    if not w.is_dir(): raise ValueError('Unknown worktree')
    return w

def init(root, repo, run):
    check_name(run)
    if git(repo,'status','--porcelain'): raise ValueError('Source repository must be clean')
    if git(repo,'rev-parse','--is-inside-work-tree')!='true': raise ValueError('Not a git repository')
    w=root/'.aies-p0'/'worktrees'/run
    w.parent.mkdir(parents=True,exist_ok=True)
    if w.exists(): raise ValueError('Worktree exists')
    base=git(repo,'rev-parse','HEAD')
    git(repo,'worktree','add','--detach',str(w),base)
    c=db(root);event(c,'worktree-created',run,{'base':base,'path':str(w)});c.close()
    print(json.dumps({'run':run,'worktree':str(w),'base':base}))

def prepare(root,run,changes):
    w=workspace(root,run); c=db(root)
    if not isinstance(changes,dict) or not 1<=len(changes)<=MAX_FILES: raise ValueError('Invalid change count')
    records=[]
    for rel, replacement in sorted(changes.items()):
        if not isinstance(replacement,str): raise ValueError('Expected UTF-8 string')
        b=replacement.encode('utf-8'); old=read(w,rel)
        if len(b)>MAX_BYTES or b'\0' in b or b==old: raise ValueError('Invalid replacement')
        records.append({'path':rel,'before':sha(old),'after':sha(b),'replacement':replacement})
    pid=secrets.token_hex(16); base=git(w,'rev-parse','HEAD')
    payload={'id':pid,'run':run,'base':base,'changes':records}
    raw=json.dumps(payload,sort_keys=True,ensure_ascii=False).encode()
    directory=root/'.aies-p0'/'proposals';directory.mkdir(parents=True,exist_ok=True)
    out=directory/(pid+'.json')
    with out.open('x',encoding='utf-8') as f: json.dump(payload,f,ensure_ascii=False,sort_keys=True)
    os.chmod(out,0o600)
    c.execute('INSERT INTO proposals VALUES(?,?,?,?,?,?,?)',(pid,run,'PREPARED',time.time()+TTL,sha(raw),None,base))
    event(c,'prepared',run,{'id':pid,'files':[r['path'] for r in records]});c.close()
    print(json.dumps({'id':pid,'digest':sha(raw),'files':[r['path'] for r in records]}))

def proposal(root,pid):
    if len(pid)!=32 or any(c not in '0123456789abcdef' for c in pid): raise ValueError('Invalid proposal ID')
    c=db(root); row=c.execute('SELECT run,state,expires,digest,approval,base FROM proposals WHERE id=?',(pid,)).fetchone()
    if row is None: raise ValueError('Unknown proposal')
    f=root/'.aies-p0'/'proposals'/(pid+'.json'); payload=json.loads(f.read_text())
    if payload['id']!=pid or payload['run']!=row[0] or sha(json.dumps(payload,sort_keys=True,ensure_ascii=False).encode())!=row[3]: raise ValueError('Proposal tampered')
    return c,row,payload

def diff(root,pid):
    c,row,p=proposal(root,pid); w=workspace(root,p['run'])
    for change in p['changes']:
        before=read(w,change['path']).decode('utf-8')
        print(''.join(difflib.unified_diff(before.splitlines(True),change['replacement'].splitlines(True),fromfile='a/'+change['path'],tofile='b/'+change['path'])))
    c.close()

def approve(root,pid,digest,actor):
    if not actor or len(actor)>100: raise ValueError('Actor required')
    c,row,p=proposal(root,pid)
    if row[1]!='PREPARED' or time.time()>row[2] or row[3]!=digest: raise ValueError('Invalid approval state/digest')
    approval=secrets.token_hex(32)
    c.execute('UPDATE proposals SET state=?,approval=? WHERE id=? AND state=?',('APPROVED',sha(approval.encode()),pid,'PREPARED'))
    event(c,'approved',p['run'],{'id':pid,'actor':actor,'digest':digest});c.close()
    print(json.dumps({'id':pid,'oneTimeApproval':approval}))

def apply(root,pid,approval):
    c,row,p=proposal(root,pid);w=workspace(root,p['run'])
    if row[1]!='APPROVED' or time.time()>row[2] or not row[4] or not secrets.compare_digest(row[4],sha(approval.encode())): raise ValueError('Not approved / expired')
    if git(w,'rev-parse','HEAD')!=p['base'] or git(w,'status','--porcelain'): raise ValueError('Worktree changed; abort')
    # Durable transition before touching any project file. Crash requires manual recovery.
    changed=c.execute('UPDATE proposals SET state=?,approval=NULL WHERE id=? AND state=?',('APPLYING',pid,'APPROVED')).rowcount
    if changed!=1: raise ValueError('Proposal already consumed')
    event(c,'apply-started',p['run'],{'id':pid})
    originals=[]
    try:
      for change in p['changes']:
          old=read(w,change['path'])
          if sha(old)!=change['before']: raise ValueError('Stale source')
          originals.append((path_under(w,change['path']),old,change['replacement'].encode()))
    except BaseException:
      c.execute('UPDATE proposals SET state=? WHERE id=?',('RECOVERY_REQUIRED',pid))
      event(c,'apply-preflight-failed',p['run'],{'id':pid});c.close();raise
    # Fail closed if anything changes after the preflight; rollback on write failure.
    written=[]
    try:
        for target,old,new in originals:
            if read(w,str(target.relative_to(w)))!=old: raise ValueError('Concurrent change')
            with tempfile.NamedTemporaryFile(dir=target.parent,prefix='.aies-',delete=False) as f:
                tmp=pathlib.Path(f.name);f.write(new);f.flush();os.fsync(f.fileno())
            os.chmod(tmp,target.stat().st_mode & 0o777)
            os.replace(tmp,target);written.append((target,old))
        c.execute('UPDATE proposals SET state=? WHERE id=? AND state=?',('APPLIED',pid,'APPLYING'))
        event(c,'applied',p['run'],{'id':pid,'files':[x['path'] for x in p['changes']]})
    except BaseException:
        for target,old in reversed(written):
            with tempfile.NamedTemporaryFile(dir=target.parent,prefix='.aies-rollback-',delete=False) as f:
                temp=pathlib.Path(f.name);f.write(old);f.flush();os.fsync(f.fileno())
            os.replace(temp,target)
        c.execute('UPDATE proposals SET state=? WHERE id=?',('RECOVERY_REQUIRED',pid))
        event(c,'rollback-attempted',p['run'],{'id':pid});raise
    finally: c.close()
    print(json.dumps({'id':pid,'state':'APPLIED','worktree':str(w)}))

def status(root):
    c=db(root)
    rows=c.execute('SELECT id,run,state,expires FROM proposals ORDER BY rowid DESC LIMIT 100').fetchall()
    print(json.dumps([{'id':r[0],'run':r[1],'state':r[2],'expires':r[3]} for r in rows]))
    c.close()

def test(root,run,image,cmd):
    w=workspace(root,run);c=db(root)
    if not shutil.which('docker'): raise ValueError('Docker required')
    if not image.startswith('sha256:') or len(image)!=71: raise ValueError('Image must be pinned by sha256 digest')
    if not cmd or len(cmd)>10: raise ValueError('Explicit argv required')
    # No shell, no network, no privileges, memory and PID limits, read-only rootfs.
    argv=['docker','run','--rm','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--pids-limit','128','--memory','1g','--cpus','1','--user','65534:65534','--tmpfs','/tmp:rw,nosuid,nodev,size=64m','--mount',f'type=bind,src={w},dst=/workspace,readonly','--workdir','/workspace',image,*cmd]
    try:
        p=subprocess.run(argv,capture_output=True,text=True,timeout=180)
        result={'exitCode':p.returncode,'stdout':p.stdout[-6000:],'stderr':p.stderr[-6000:]}
    except subprocess.TimeoutExpired: result={'exitCode':124,'stderr':'Timed out'}
    event(c,'sandbox-test',run,{'exitCode':result['exitCode'],'image':image,'argv':cmd});c.close()
    print(json.dumps(result));return result['exitCode']

def main():
    a=argparse.ArgumentParser();a.add_argument('--state-root',required=True,type=pathlib.Path)
    s=a.add_subparsers(dest='action',required=True)
    p=s.add_parser('init');p.add_argument('--repo',required=True,type=pathlib.Path);p.add_argument('--run',required=True)
    p=s.add_parser('prepare');p.add_argument('--run',required=True);p.add_argument('--changes',required=True,type=pathlib.Path)
    p=s.add_parser('diff');p.add_argument('--id',required=True)
    p=s.add_parser('approve');p.add_argument('--id',required=True);p.add_argument('--digest',required=True);p.add_argument('--actor',required=True)
    p=s.add_parser('apply');p.add_argument('--id',required=True);p.add_argument('--approval',required=True)
    s.add_parser('status')
    p=s.add_parser('test');p.add_argument('--run',required=True);p.add_argument('--image',required=True);p.add_argument('command',nargs=argparse.REMAINDER)
    x=a.parse_args();root=x.state_root.expanduser().resolve();root.mkdir(parents=True,exist_ok=True)
    if x.action=='init': init(root,x.repo.resolve(),x.run)
    elif x.action=='prepare':prepare(root,x.run,json.loads(x.changes.read_text()))
    elif x.action=='diff':diff(root,x.id)
    elif x.action=='approve':approve(root,x.id,x.digest,x.actor)
    elif x.action=='apply':apply(root,x.id,x.approval)
    elif x.action=='status':status(root)
    elif x.action=='test':return test(root,x.run,x.image,x.command)
    return 0
if __name__=='__main__':
    try:sys.exit(main())
    except (ValueError,RuntimeError,OSError,sqlite3.Error) as e: print('ERROR:',e,file=sys.stderr);sys.exit(2)
