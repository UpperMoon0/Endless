"""Protect the real optimized-renderer compatibility matrix from silent coverage loss."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/render-smoke.yml"
RUNNER = ROOT / "tools/render_smoke_test.py"

class RendererMatrixContractTest(unittest.TestCase):
    def test_every_supported_line_has_an_optimized_renderer(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        rows = re.findall(
            r"^\s*-\s*\{target:\s*([^,]+),\s*backend:\s*([^,]+),\s*renderer:\s*([^,}\s]+)",
            source, re.MULTILINE)
        self.assertTrue(rows, "Renderer matrix is missing or changed format")
        self.assertEqual(len(rows), len(set(rows)), "Duplicate renderer matrix lane")
        supported = {
            ("forge-1.20.1", "embeddium"),
            ("fabric-1.20.1", "sodium"),
            ("fabric-1.21.1", "sodium"),
            ("neoforge-1.21.1", "sodium"),
            ("neoforge-1.21.1", "embeddium"),
            ("neoforge-26.1.2", "sodium"),
        }
        actual = {(target.strip(), renderer.strip()) for target, _, renderer in rows}
        self.assertFalse(supported - actual, f"Missing optimized renderer lanes: {supported - actual}")

    def test_matrix_requires_native_framebuffer_evidence_not_server_only_state(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        runner = RUNNER.read_text(encoding="utf-8")
        for needle in (
            "python3 tools/render_smoke_test.py",
            '--renderer "$RENDERER"',
            "Require exact-head rendering receipt",
            "receipt['head'] == os.environ['EXACT_HEAD']",
            "receipt['dirty'] is False",
        ):
            self.assertIn(needle, workflow)
        for scenario in (
            "fixture--80", "fixture-512", "fixture-1000000",
            "fixture-reload", "12-fixture-edit",
        ):
            self.assertIn(scenario, runner)
        self.assertIn("not record[renderer]", runner)
        self.assertIn("Missing or duplicate native framebuffer captures", runner)
        self.assertIn("complete mesh output", runner)

if __name__ == "__main__":
    unittest.main()
