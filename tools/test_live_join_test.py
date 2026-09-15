import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import ci_policy
import live_join_test as live


class VerificationPolicyTest(unittest.TestCase):
    def test_documentation_only_skips_boots(self):
        self.assertFalse(ci_policy.needs_live(["README.md", "docs/testing.md", "changelogs/v0.5.txt"]))

    def test_runtime_build_workflow_and_unknown_paths_require_boots(self):
        for path in ["build.gradle", "settings.gradle", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
                     "tools/test_live_join_test.py", ".github/workflows/live-join-test.yml",
                     "common/src/main/resources/test.txt", "unknown.file"]:
            with self.subTest(path=path):
                self.assertTrue(ci_policy.needs_live(["README.md", path]))
        self.assertTrue(ci_policy.needs_live([]))

    def test_unique_matrix(self):
        self.assertEqual(len(live.SCENARIOS), len({s.id for s in live.SCENARIOS}))
        self.assertEqual(12, len(live.SCENARIOS) * len(live.TARGETS))

    def test_million_gameplay_cannot_degrade_to_join_only(self):
        scenario = next(s for s in live.SCENARIOS if s.id == "million-gameplay")
        self.assertEqual(live.MILLION_BUILD_HEIGHT, scenario.expected)
        self.assertTrue(scenario.gameplay)
        for marker in ["ENDLESS_PATHFINDING_PASS", "ENDLESS_CLIENT_INTERACTION_SERVER_PASS", "ENDLESS_HIGH_Y_SERVER_PASS"]:
            self.assertIn(marker, scenario.required_server_markers)
        for edge in ["lower", "upper"]:
            self.assertIn(f"ENDLESS_CLIENT_PREDICTION_PASS edge={edge}", scenario.required_client_markers)
            self.assertIn(f"ENDLESS_RENDER_PATH_PASS edge={edge}", scenario.required_client_markers)

    def test_scenario_environment_does_not_leak(self):
        with patch.dict(live.os.environ, {"ENDLESS_TEST_WAYSTONES": "true", "ENDLESS_TEST_EXTREME": "true"}):
            for s in live.SCENARIOS:
                env = live.scenario_env(s)
                self.assertEqual(str(s.gameplay).lower(), env["ENDLESS_TEST_EXTREME"])
                self.assertEqual(str(s.gameplay).lower(), env["ENDLESS_TEST_WAYSTONES"])
                self.assertEqual(str(s.id == "far-envelope").lower(), env["ENDLESS_TEST_FAR"])
                self.assertEqual("", env["ENDLESS_TEST_COLD_RESTART_PHASE"])

    def test_cold_restart_uses_far_envelope(self):
        scenario = next(s for s in live.SCENARIOS if s.cold_restart)
        self.assertEqual(live.FAR_BUILD_HEIGHT, scenario.expected)

    def test_receipts_require_complete_exact_head(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for target in live.TARGETS:
                for scenario in live.SCENARIOS:
                    (directory / f"{target}--{scenario.id}.pass").write_text("abc123\n")
            live.verify_receipts(directory, "abc123")
            receipt = next(directory.glob("*.pass"))
            receipt.write_text("old-head\n")
            with self.assertRaisesRegex(RuntimeError, "stale"):
                live.verify_receipts(directory, "abc123")
            receipt.unlink()
            with self.assertRaisesRegex(RuntimeError, "missing"):
                live.verify_receipts(directory, "abc123")
            receipt.write_text("abc123\n")
            (directory / "unexpected.pass").write_text("abc123\n")
            with self.assertRaisesRegex(RuntimeError, "extra"):
                live.verify_receipts(directory, "abc123")

    def test_checkout_lock_excludes_concurrent_runner_and_releases(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with live.checkout_lock(root):
                with self.assertRaisesRegex(RuntimeError, "owns this checkout"):
                    with live.checkout_lock(root):
                        self.fail("second runner acquired the checkout")
            with live.checkout_lock(root):
                pass


class OutputEvidenceTest(unittest.TestCase):
    def pump(self, text):
        process = Mock(stdout=io.StringIO(text))
        process.poll.return_value = 0
        with patch("builtins.print"):
            pump = live.OutputPump(process, "test")
            pump.thread.join(timeout=2)
        return pump

    def test_history_retains_consumed_markers(self):
        pump = self.pump("ready\nmechanics pass\n")
        self.assertEqual("ready\n", pump.wait_for(("ready",), 1))
        pump.wait_until_seen(("mechanics pass",), 1)

    def test_missing_marker_fails_even_when_process_exited_cleanly(self):
        pump = self.pump("joined\n")
        with self.assertRaisesRegex(RuntimeError, "missing required"):
            pump.wait_until_seen(("render pass",), 1)

    def test_failure_wins_over_pass_in_history(self):
        pump = self.pump("PASS\nFAIL\n")
        with self.assertRaisesRegex(RuntimeError, "reported failure"):
            pump.wait_until_seen(("PASS",), 1, ("FAIL",))

    def test_server_failure_wins_over_client_pass(self):
        server = self.pump("ENDLESS_HIGH_Y_SERVER_FAIL fixture\n")
        client = self.pump(live.PASS_MARKER + "\n")
        with self.assertRaisesRegex(RuntimeError, "server reported failure"):
            live.wait_for_live_join_outcome(client, server, 1, "test")

    def test_mixin_crash_fails_before_ready_timeout(self):
        pump = self.pump("Critical injection failure\n")
        with self.assertRaisesRegex(RuntimeError, "reported failure"):
            pump.wait_for(live.SERVER_READY_MARKERS, 1, live.SERVER_FATAL_MARKERS)

    def test_failure_receipt_survives_exception(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with patch.object(live.subprocess, "check_output", side_effect=["abc123\n", " M changed\n"]), \
                 patch.object(live, "_run_scenario", side_effect=RuntimeError("test crash")):
                with self.assertRaisesRegex(RuntimeError, "test crash"):
                    live.run_scenario(root, "fabric-1.20.1", "fabric", live.SCENARIOS[0], 1)
            result = json.loads(next(root.rglob("result.json")).read_text())
            self.assertEqual("fail", result["status"])
            self.assertTrue(result["dirty"])
            self.assertEqual("abc123", result["head"])
            self.assertIn("test crash", result["error"])


if __name__ == "__main__":
    unittest.main()
