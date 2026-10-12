import os
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import aies_tools as t

class PolicyTests(unittest.TestCase):
    def test_loopback(self):
        t.validate_local_url('http://127.0.0.1:8080')
        t.validate_local_url('http://localhost:3000/')
    def test_external_blocked(self):
        for url in ['http://example.com', 'file:///etc/passwd', 'http://127.0.0.1.evil.com', 'http://user:pass@localhost']:
            with self.assertRaises(ValueError):
                t.validate_local_url(url)
    def test_escape_blocked(self):
        with self.assertRaises(ValueError):
            t.inside('../outside')
    def test_output_restricted(self):
        with self.assertRaises(ValueError):
            t.ensure_artifact('README.md')

if __name__ == '__main__':
    unittest.main()
