import importlib.util
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('aies_worktree',Path(__file__).resolve().parents[1]/'tools/aies_worktree.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class PolicyTest(unittest.TestCase):
    def test_safe_paths(self):
        self.assertEqual(str(m.safe_path('src/App.java')),'src/App.java')
    def test_denied_paths(self):
        for path in ('../secret.py','.env','build/out.java','.git/config','/tmp/x.py','src/evil.sh','src\\evil.py'):
            with self.subTest(path=path),self.assertRaises(ValueError):m.safe_path(path)
    def test_state_id(self):
        with self.assertRaises(ValueError):m.state_file('../../foo')

if __name__=='__main__':unittest.main()
