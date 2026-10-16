import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts' / 'aies-sqlite-inventory.py'
spec = importlib.util.spec_from_file_location('aies_sqlite_inventory', SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class SqliteInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'repo'
        self.root.mkdir()
        (self.root / 'settings.gradle.kts').write_text('')
        (self.root / 'data' / 'agent-communications').mkdir(parents=True)
        self.db = self.root / 'data' / 'agent-communications' / 'planning.sqlite'
        with sqlite3.connect(self.db) as c:
            c.execute('CREATE TABLE planning_workflows(id TEXT PRIMARY KEY)')
            c.execute("INSERT INTO planning_workflows VALUES('keep')")
    def tearDown(self):
        self.temp.cleanup()
    def test_read_only_inventory_and_backup(self):
        report = mod.run(self.root)
        self.assertEqual('referenced', report['entries'][0]['classification'])
        self.assertEqual(1, report['entries'][0]['tables']['planning_workflows'])
        self.assertTrue(self.db.exists())
        backup = Path(self.temp.name) / 'backup'
        report = mod.run(self.root, backup)
        copied = Path(report['entries'][0]['backup'])
        self.assertTrue(copied.exists())
        with sqlite3.connect(copied) as c:
            self.assertEqual(1, c.execute('SELECT count(*) FROM planning_workflows').fetchone()[0])
    def test_never_quarantine_referenced_db(self):
        backup = Path(self.temp.name) / 'backup'
        report = mod.run(self.root, backup, True, True)
        self.assertTrue(self.db.exists())
        self.assertNotIn('quarantine', report['entries'][0])
    def test_quarantine_requires_confirmation_and_backup(self):
        with self.assertRaises(ValueError):
            mod.run(self.root, quarantine=True)
    def test_invalid_header_does_not_delete(self):
        bad = self.root / 'data' / 'broken.sqlite'
        bad.write_bytes(b'not sqlite')
        report = mod.run(self.root)
        self.assertEqual('invalid_header', next(e['status'] for e in report['entries'] if e['name']=='broken.sqlite'))
        self.assertTrue(bad.exists())

if __name__ == '__main__':
    unittest.main()
