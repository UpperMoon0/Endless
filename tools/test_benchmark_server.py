import unittest
from benchmark_server import select_sprint


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


if __name__ == "__main__":
    unittest.main()
