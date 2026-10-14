import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/aies-integration-inventory.py'
spec = importlib.util.spec_from_file_location('inventory', SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class InventoryTest(unittest.TestCase):
    def test_missing_components_are_reported_not_invented(self):
        with tempfile.TemporaryDirectory() as d:
            result = module.inventory(Path(d))
            self.assertFalse(result['components'][0]['exists'])
            self.assertEqual([], result['feature_flags'])

    def test_references_and_flags_are_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            path = root / module.TARGETS['eventbus-v1']
            path.parent.mkdir(parents=True)
            path.write_text('class SpecialistEventBus {}')
            consumer = path.parent / 'Consumer.java'
            consumer.write_text('class Consumer { SpecialistEventBus bus; }')
            cfg = root / 'apps/control-plane/src/main/resources/application.properties'
            cfg.parent.mkdir(parents=True)
            cfg.write_text('aies.specialists.enabled=${AIES_SPECIALISTS_ENABLED:false}\n')
            one = module.inventory(root)
            self.assertEqual(one, module.inventory(root))
            self.assertIn(str(consumer.relative_to(root)), one['components'][0]['java_reference_files'])
            self.assertEqual('aies.specialists.enabled', one['feature_flags'][0]['key'])

if __name__ == '__main__':
    unittest.main()
