import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import check_create_mixin_refmap as gate


class CreatePackagedRefmapTest(unittest.TestCase):
    def check(self, mappings):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.jar"
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("common-refmap.json", json.dumps({"mappings": mappings}))
            return gate.check_jar(path)

    def valid(self):
        return {gate.PREFIX + name: dict(entries) for name, entries in gate.EXPECTED.items()}

    def test_shipped_runtime_srg_selectors_pass(self):
        self.assertEqual([], self.check(self.valid()))

    def test_optional_mod_mixin_without_minecraft_remap_fails(self):
        mappings = self.valid()
        del mappings[gate.PREFIX + "CreateContraptionMixin"]
        errors = self.check(mappings)
        self.assertEqual(1, len(errors))
        self.assertIn("CreateContraptionMixin", errors[0])

    def test_development_named_selector_is_not_production_evidence(self):
        mappings = self.valid()
        key = gate.PREFIX + "CreatePulleyBlockEntityMixin"
        selector = next(iter(mappings[key]))
        mappings[key][selector] = selector
        self.assertEqual(1, len(self.check(mappings)))


if __name__ == "__main__":
    unittest.main()
