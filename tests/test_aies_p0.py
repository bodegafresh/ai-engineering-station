import importlib.util, pathlib, subprocess, tempfile, unittest, json, io, contextlib
M=pathlib.Path(__file__).resolve().parents[1]/'scripts'/'aies_p0.py'
spec=importlib.util.spec_from_file_location('aies_p0',M);p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class TestP0(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
  self.root=pathlib.Path(self.tmp.name);self.repo=self.root/'repo';self.repo.mkdir()
  subprocess.run(['git','init','-q',str(self.repo)],check=True)
  subprocess.run(['git','-C',str(self.repo),'config','user.email','test@example.org'],check=True)
  subprocess.run(['git','-C',str(self.repo),'config','user.name','Test'],check=True)
  (self.repo/'App.java').write_text('class App {}\n')
  subprocess.run(['git','-C',str(self.repo),'add','.'],check=True)
  subprocess.run(['git','-C',str(self.repo),'commit','-qm','init'],check=True)
  self.state=self.root/'state';p.init(self.state,self.repo,'run1')
 def proposal(self):
  with contextlib.redirect_stdout(io.StringIO()) as o:p.prepare(self.state,'run1',{'App.java':'class App { int x; }\n'})
  return json.loads(o.getvalue())
 def test_approval_and_worktree(self):
  q=self.proposal();w=p.workspace(self.state,'run1');self.assertEqual((w/'App.java').read_text(),'class App {}\n')
  with self.assertRaises(ValueError):p.apply(self.state,q['id'],'wrong')
  with contextlib.redirect_stdout(io.StringIO()) as o:p.approve(self.state,q['id'],q['digest'],'human')
  token=json.loads(o.getvalue())['oneTimeApproval']
  p.apply(self.state,q['id'],token)
  self.assertEqual((w/'App.java').read_text(),'class App { int x; }\n')
  self.assertEqual((self.repo/'App.java').read_text(),'class App {}\n')
  with self.assertRaises(ValueError):p.apply(self.state,q['id'],token)
 def test_tamper_rejected(self):
  q=self.proposal();f=self.state/'.aies-p0'/'proposals'/(q['id']+'.json');f.write_text(f.read_text().replace('int x','int evil'))
  with self.assertRaises(ValueError):p.diff(self.state,q['id'])
 def test_traversal(self):
  for rel in ['../App.java','.env','dir/../../App.java','secret.key']:
   with self.subTest(rel=rel),self.assertRaises(ValueError):p.path_under(p.workspace(self.state,'run1'),rel)
 def test_symlink(self):
  w=p.workspace(self.state,'run1');(w/'evil.java').symlink_to(self.repo/'App.java')
  with self.assertRaises(ValueError):p.read(w,'evil.java')
 def test_stale(self):
  q=self.proposal()
  with contextlib.redirect_stdout(io.StringIO()) as o:p.approve(self.state,q['id'],q['digest'],'human')
  token=json.loads(o.getvalue())['oneTimeApproval'];(p.workspace(self.state,'run1')/'App.java').write_text('other')
  with self.assertRaises(ValueError):p.apply(self.state,q['id'],token)
if __name__=='__main__':unittest.main()

class TestHardening(TestP0):
 def test_sensitive_name(self):
  for name in ('api_token.java','private_key.java','passwords.json'):
   with self.subTest(name=name), self.assertRaises(ValueError):
    p.path_under(p.workspace(self.state,'run1'),name,False)
 def test_symlink_parent(self):
  w=p.workspace(self.state,'run1')
  (w/'linked').symlink_to(self.repo,target_is_directory=True)
  with self.assertRaises(ValueError):p.path_under(w,'linked/App.java')
 def test_state_consumed_on_preflight_failure(self):
  q=self.proposal()
  with contextlib.redirect_stdout(io.StringIO()) as o:p.approve(self.state,q['id'],q['digest'],'human')
  token=json.loads(o.getvalue())['oneTimeApproval']
  (p.workspace(self.state,'run1')/'App.java').write_text('changed')
  with self.assertRaises(ValueError):p.apply(self.state,q['id'],token)
  c=p.db(self.state)
  self.assertEqual('APPROVED',c.execute('SELECT state FROM proposals WHERE id=?',(q['id'],)).fetchone()[0])
  c.close()
