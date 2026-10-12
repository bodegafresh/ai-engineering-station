import contextlib
import hashlib
import importlib.util
import io
import json
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('aies_agent_bridge', ROOT / 'scripts' / 'aies_agent_bridge.py')
bridge = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bridge)

class AgentBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = pathlib.Path(self.temp.name)
        self.repo = self.base / 'repo'
        self.repo.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        for key, value in [('user.email','test@example.org'), ('user.name','Test')]:
            subprocess.run(['git','-C',str(self.repo),'config',key,value],check=True)
        (self.repo / 'App.java').write_text('class App {}\n')
        subprocess.run(['git','-C',str(self.repo),'add','.'],check=True)
        subprocess.run(['git','-C',str(self.repo),'commit','-qm','initial'],check=True)
        self.state = self.base / 'state'
        self.p0 = bridge.load_p0()
        with contextlib.redirect_stdout(io.StringIO()):
            self.p0.init(self.state, self.repo, 'run1')
        self.old = hashlib.sha256(b'class App {}\n').hexdigest()

    def document(self, **change):
        item = {'path':'App.java','beforeSha256':self.old,'replacement':'class App { int n; }\n'}
        item.update(change)
        return {'schemaVersion':1,'runId':'run1','objective':'Improve app','changes':[item]}

    def validate(self, doc):
        return bridge.validate(json.dumps(doc).encode(), 'run1', self.state)

    def test_valid_proposal_does_not_write_workspace(self):
        path = self.base / 'agent.json'
        path.write_text(json.dumps(self.document()))
        result = bridge.bridge(self.state,'run1',path)
        self.assertEqual(result['origin'],'untrusted-agent-proposal')
        self.assertEqual((self.p0.workspace(self.state,'run1')/'App.java').read_text(),'class App {}\n')
        self.assertEqual(self.p0.proposal(self.state,result['id'])[1][1],'PREPARED')

    def test_stale_hash(self):
        with self.assertRaisesRegex(ValueError,'Stale'):
            self.validate(self.document(beforeSha256='0'*64))

    def test_traversal(self):
        with self.assertRaises(ValueError):
            self.validate(self.document(path='../App.java'))

    def test_symlink(self):
        w = self.p0.workspace(self.state,'run1')
        (w/'evil.java').symlink_to(self.repo/'App.java')
        with self.assertRaises(ValueError):
            self.validate(self.document(path='evil.java'))

    def test_duplicate(self):
        doc = self.document()
        doc['changes'].append(dict(doc['changes'][0]))
        with self.assertRaises(ValueError):
            self.validate(doc)

    def test_unexpected_fields(self):
        doc = self.document()
        doc['execute'] = 'rm -rf /'
        with self.assertRaises(ValueError):
            self.validate(doc)

    def test_run_mismatch(self):
        doc = self.document()
        doc['runId'] = 'run2'
        with self.assertRaises(ValueError):
            self.validate(doc)

    def test_oversized(self):
        with self.assertRaises(ValueError):
            self.validate(self.document(replacement='x'*32769))

    def test_noop(self):
        with self.assertRaises(ValueError):
            self.validate(self.document(replacement='class App {}\n'))

    def test_no_approval_created(self):
        path = self.base/'agent.json'; path.write_text(json.dumps(self.document()))
        result = bridge.bridge(self.state,'run1',path)
        c = self.p0.db(self.state)
        row = c.execute('SELECT state, approval FROM proposals WHERE id=?',(result['id'],)).fetchone()
        c.close()
        self.assertEqual(row,('PREPARED',None))

if __name__ == '__main__':
    unittest.main()
