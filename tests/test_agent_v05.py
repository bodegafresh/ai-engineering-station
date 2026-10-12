import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC=importlib.util.spec_from_file_location('aies_agent',Path(__file__).resolve().parents[1]/'tools/aies_agent.py')
a=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(a)

class AgentPolicyTest(unittest.TestCase):
    def test_unknown_tool(self):
        with self.assertRaises(ValueError): a.validate({'tool':'shell.exec','output':'.aies/artifacts/a.png'})
    def test_external_url(self):
        with self.assertRaises(ValueError): a.validate({'tool':'browser.inspect','url':'https://example.com','output':'.aies/artifacts/a.png'})
    def test_path_traversal(self):
        with self.assertRaises(ValueError): a.validate({'tool':'browser.inspect','url':'http://127.0.0.1:8765/','output':'../../tmp/x.png'})
    def test_local_browser(self):
        result=a.validate({'tool':'browser.inspect','url':'http://127.0.0.1:8765/','output':'.aies/artifacts/v05-browser.png'})
        self.assertEqual(result['tool'],'browser.inspect')
    def test_diagram_source(self):
        result=a.validate({'tool':'diagram.render','source':'examples/architecture.puml','output':'.aies/artifacts/v05-diagram.svg'})
        self.assertEqual(result['tool'],'diagram.render')

if __name__=='__main__': unittest.main()
