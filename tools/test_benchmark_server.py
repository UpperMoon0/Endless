import csv
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from benchmark_server import datapack, run, select_sprint


class ExactTickWindowTest(unittest.TestCase):
    def test_equal_wall_timestamps_do_not_mix_warmup_normal_and_measured_ticks(self):
        rows = [{"wall_ms": "100", "sprint_id": str(i)} for i in (1, 1, 0, 2, 2, 0, 3)]
        self.assertEqual(select_sprint(rows, 2, 2), rows[3:5])

    def test_missing_or_extra_ticks_fail_instead_of_trimming_the_sample(self):
        rows = [{"sprint_id": "2"}, {"sprint_id": "2"}]
        for expected in (1, 3):
            with self.assertRaises(RuntimeError):
                select_sprint(rows, 2, expected)
        with self.assertRaises(RuntimeError):
            select_sprint(rows, 4, 2)


class FakeServer:
    """Exercise run() with complete sprint CSVs, independently of workload success."""

    def __init__(self, fault=None, extra_ticks=0):
        self.fault = fault
        self.extra_ticks = extra_ticks
        self.scores = {"queries": 0, "edits": 0, "fills": 0, "enabled": 0}
        self.returncode = None
        self.sprint_id = 0
        self.commands = []
        self.closed = False

    def start(self, command, cwd, stdout, stderr):
        self.directory = cwd
        stdout.write("ENDLESS_BENCH_TICK_HOOK_PASS\nENDLESS_BENCH_SPRINT_HOOK_PASS\nDone (1s)!\n")
        stdout.flush()
        self.ticks = cwd / "ticks.csv"
        self.ticks.write_text("sprint_id,tick_ns,cpu_ns,allocated_bytes\n")
        return self

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        return self.returncode

    def terminate(self):
        self.returncode = -15

    def close(self):
        self.closed = True

    def request(self, command):
        self.commands.append(command)
        words = command.split()
        if command.startswith("scoreboard players set "):
            self.scores[words[3]] = int(words[5])
            return f"Set [{words[4]}] for {words[3]} to {words[5]}"
        if command.startswith("scoreboard players get "):
            player = words[3]
            return f"{player} has {self.scores[player]} [bench]"
        if command.startswith("fill "):
            return "Successfully filled 2048 block(s)"
        if command == "function bench:heights":
            self.scores["queries"] += 256
            return "Running function bench:heights"
        if command.startswith("tick sprint "):
            self.sprint_id += 1
            amount = int(words[-1])
            with self.ticks.open("a") as file:
                writer = csv.writer(file)
                writer.writerows((self.sprint_id, 100, 80, 64) for _ in range(amount))
            count = amount + self.extra_ticks
            enabled = self.scores["enabled"]
            if enabled == 2 and not (self.fault == "missing_measured_reads" and self.sprint_id == 4):
                # Surplus warmup reads must not rescue a missing measured phase.
                multiplier = 10 if self.fault == "missing_measured_reads" else 1
                self.scores["queries"] += 256 * count * multiplier
            if enabled == 1 and self.fault != "missing_edits":
                self.scores["edits"] += count
                if self.fault != "missing_edit_queries":
                    self.scores["queries"] += 256 * count
                # A rejected or partial fill must not count as a full 2048-block edit.
                failed = self.fault in ("failed_fills", "partial_fills")
                failed |= self.fault == "failed_measured_fill" and self.sprint_id == 6
                if not failed:
                    self.scores["fills"] += count
            return "Sprint started"
        if command == "tick query":
            return "The game is running normally at 20 ticks per second"
        if command.startswith("execute if block "):
            return "Set [bench] for proof to 1"
        if command == "stop":
            self.returncode = 0
        return "OK"


class WorkloadExecutionTest(unittest.TestCase):
    def exercise_run(self, fault=None, extra_ticks=0):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        output = Path(temporary.name)
        args = SimpleNamespace(output=output, dependency=[], port=25685,
                               java=output, vanilla=output / "server.jar",
                               fabric=output / "fabric.jar", helper=output / "helper.jar",
                               agent=output / "agent.jar", warmup=1, ticks=2)
        server = FakeServer(fault, extra_ticks)
        with patch("benchmark_server.subprocess.Popen", side_effect=server.start), \
             patch("benchmark_server.Rcon", return_value=server), \
             patch("benchmark_server.time.sleep"), patch("builtins.print"):
            try:
                result = run(args, "vanilla", None, 0)
            except RuntimeError:
                self.assertFalse((output / "vanilla-dense-0/result.json").exists())
                self.assertTrue(server.closed)
                raise
        self.assertEqual(json.loads((output / "vanilla-dense-0/result.json").read_text()), result)
        return result

    def test_reads_cannot_hide_a_missing_edit_workload(self):
        with self.assertRaisesRegex(RuntimeError, "edits"):
            self.exercise_run("missing_edits")

    def test_queries_and_later_setblock_cannot_hide_failed_or_partial_fills(self):
        for fault in ("failed_fills", "partial_fills", "failed_measured_fill"):
            with self.subTest(fault=fault), self.assertRaisesRegex(RuntimeError, "fills"):
                self.exercise_run(fault)

    def test_edit_attempts_require_all_height_queries(self):
        with self.assertRaisesRegex(RuntimeError, "queries"):
            self.exercise_run("missing_edit_queries")

    def test_warmup_cannot_hide_missing_measured_reads(self):
        with self.assertRaisesRegex(RuntimeError, "reads measured"):
            self.exercise_run("missing_measured_reads")

    def test_valid_run_records_each_phase_independently(self):
        result = self.exercise_run()
        self.assertEqual(result["height_queries"], 256 + 2 * 256 * 3)
        self.assertEqual(set(result["metrics"]), {"idle", "reads", "edits"})
        edits = result["windows"][2]["counts"]
        self.assertEqual(edits["warmup"], {"queries": 256, "edits": 1, "fills": 1})
        self.assertEqual(edits["measured"], {"queries": 512, "edits": 2, "fills": 2})

    def test_normal_ticks_between_rcon_requests_are_allowed_but_still_verified(self):
        result = self.exercise_run(extra_ticks=1)
        self.assertEqual(result["windows"][2]["counts"]["measured"]["fills"], 3)
        self.assertEqual(result["metrics"]["edits"]["ticks"], 2)


class DatapackProofTest(unittest.TestCase):
    def test_dense_and_sparse_edits_count_only_complete_native_fills(self):
        for sparse, y in ((False, 80), (True, 4096)):
            with self.subTest(sparse=sparse), tempfile.TemporaryDirectory() as temporary:
                world = Path(temporary)
                datapack(world, sparse)
                source = (world / "datapacks/benchmark/data/bench/function/edits.mcfunction").read_text()
                self.assertIn("scoreboard players add edits bench 1\n", source)
                self.assertIn("scoreboard players set filled bench 0\n", source)
                for material in ("stone", "air"):
                    self.assertIn(f"store result score filled bench run fill 0 {y} 0 31 {y+1} 31 minecraft:{material}", source)
                self.assertIn("execute if score filled bench matches 2048 run scoreboard players add fills bench 1\n", source)


if __name__ == "__main__":
    unittest.main()
