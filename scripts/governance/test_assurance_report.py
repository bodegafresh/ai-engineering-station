import pathlib
import tempfile
import unittest
from assurance_report import inspect

class AssuranceReportTest(unittest.TestCase):
    def test_missing_evidence_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            result=inspect(pathlib.Path(temp))
            self.assertFalse(result['automated_evidence_complete'])
            self.assertEqual(result['matrix']['revocation'][0]['state'],'MISSING')
    def test_malformed_xml_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            pathlib.Path(temp,'TEST-broken.xml').write_text('<broken')
            result=inspect(pathlib.Path(temp))
            self.assertTrue(result['invalid_xml'])
            self.assertFalse(result['automated_evidence_complete'])
    def test_targeted_run_not_release_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            pathlib.Path(temp,'TEST-one.xml').write_text('<testsuite><testcase classname="dev.aies.control.execution.GovernedDocumentationConsumerE2ETest" name="crossJvmRevocationFirstDeniesDocumentPublication()"/></testsuite>')
            result=inspect(pathlib.Path(temp))
            self.assertEqual(result['matrix']['revocation'][1]['state'],'PASS')
            self.assertFalse(result['automated_evidence_complete'])

if __name__=='__main__': unittest.main()
