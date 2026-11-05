"""Metadata-level negative controls for installed renderer JAR certification."""
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from certify_renderer_jar import certify


class RendererJarCertificationTest(unittest.TestCase):
    def test_fabric_sodium(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "renderer.jar"
            with ZipFile(path, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps({"id": "sodium", "version": "0.6.13"}))
            receipt = certify(path, "sodium")
            self.assertEqual(receipt["version"], "0.6.13")
            self.assertEqual(len(receipt["sha256"]), 64)
            with self.assertRaises(ValueError):
                certify(path, "embeddium")

    def test_neoforge_embeddium(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "renderer.jar"
            with ZipFile(path, "w") as jar:
                jar.writestr("META-INF/neoforge.mods.toml", '[[mods]]\nmodId="other"\nversion="9.0"\n[[mods]]\nmodId="embeddium"\nversion="0.3.31"\n')
            self.assertEqual(certify(path, "embeddium")["version"], "0.3.31")

    def test_placeholder_version_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "renderer.jar"
            with ZipFile(path, "w") as jar:
                jar.writestr("META-INF/mods.toml", '[[mods]]\nmodId="embeddium"\nversion="${file.jarVersion}"\n')
            with self.assertRaises(ValueError):
                certify(path, "embeddium")


if __name__ == "__main__":
    unittest.main()
