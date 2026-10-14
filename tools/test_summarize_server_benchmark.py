import unittest
from summarize_server_benchmark import aggregate


def result(repeat, ticks, value, sparse=False):
    metric = {"median": value, "mean": value, "p95": value * 2, "p99": value * 3}
    return {"label": "current", "sparse": sparse, "repeat": repeat,
            "metrics": {"edits": {"ticks": ticks, "tick_ns": metric, "cpu_ns": metric, "allocated_bytes": metric}}}


class IndependentJvmSummaryTest(unittest.TestCase):
    def test_longer_run_does_not_get_more_weight_and_sparse_work_is_separate(self):
        records = aggregate([result(0, 100, 1e6), result(1, 10000, 3e6), result(0, 100, 9e6, True)])
        self.assertEqual(records[0]["tick_median_ms"], 2)
        self.assertEqual(records[0]["tick_p95_ms"], 4)
        self.assertEqual(records[0]["tick_median_ms_range"], [1, 3])
        self.assertEqual(records[1]["tick_median_ms"], 9)

    def test_duplicate_jvm_cannot_silently_bias_a_comparison(self):
        with self.assertRaises(ValueError):
            aggregate([result(0, 100, 1e6), result(0, 100, 1e6)])


if __name__ == "__main__":
    unittest.main()
