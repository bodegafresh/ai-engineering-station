import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts' / 'aies-sqlite-recovery.py'

class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.source = self.dir / 'source.sqlite'
        with sqlite3.connect(self.source) as c:
            c.execute('CREATE TABLE items(id INTEGER PRIMARY KEY, value TEXT)')
            c.execute("INSERT INTO items(value) VALUES ('persisted')")

    def run_cli(self, *args, ok=True):
        p = subprocess.run([sys.executable, str(SCRIPT), *map(str,args)], capture_output=True, text=True)
        self.assertEqual(p.returncode == 0, ok, p.stderr)
        return p

    def test_backup_restore_and_source_unchanged(self):
        original = self.source.read_bytes()
        copy = self.dir / 'backup.sqlite'
        restored = self.dir / 'restored.sqlite'
        result = json.loads(self.run_cli('backup',self.source,copy).stdout)
        self.assertEqual(result['integrity'], 'ok')
        self.run_cli('restore',copy,restored)
        with sqlite3.connect(restored) as c:
            self.assertEqual(c.execute('SELECT value FROM items').fetchone()[0], 'persisted')
        self.assertEqual(self.source.read_bytes(), original)
        self.run_cli('restore',copy,restored,ok=False)

    def test_missing_source_and_corruption(self):
        self.run_cli('backup',self.dir/'missing.sqlite',self.dir/'new.sqlite',ok=False)
        self.assertFalse((self.dir/'new.sqlite').exists())
        bad=self.dir/'bad.sqlite'
        bad.write_bytes(b'not a sqlite database')
        self.run_cli('verify',bad,ok=False)
        self.run_cli('restore',bad,self.dir/'bad-copy.sqlite',ok=False)
        self.assertFalse((self.dir/'bad-copy.sqlite').exists())

    def test_wal_backup(self):
        writer = sqlite3.connect(self.source)
        try:
            writer.execute('PRAGMA journal_mode=WAL')
            writer.execute("INSERT INTO items(value) VALUES ('wal')")
            writer.commit()
            dest = self.dir/'wal-backup.sqlite'
            self.run_cli('backup',self.source,dest)
            with sqlite3.connect(dest) as c:
                self.assertEqual(c.execute('SELECT count(*) FROM items').fetchone()[0], 2)
        finally:
            writer.close()

if __name__ == '__main__':
    unittest.main()
