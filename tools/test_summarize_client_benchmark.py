import unittest
from summarize_client_benchmark import summarize


def receipt(repeat, value, sparse=False, frames=100):
    return {'label': 'current', 'sparse': sparse, 'repeat': repeat,
            'metrics': {'edits': {'frames': frames, **{name: value for name in
                ('average_fps', 'one_percent_low_fps', 'median_ms', 'p95_ms', 'p99_ms', 'max_ms')}}}}


class IndependentClientJvmTests(unittest.TestCase):
    def test_frame_counts_do_not_weight_jvms_and_heights_stay_separate(self):
        rows = summarize([receipt(0, 100), receipt(1, 300, frames=10000), receipt(0, 900, True)])
        self.assertEqual(rows[0]['metrics']['average_fps'], {'median': 200, 'min': 100, 'max': 300})
        self.assertEqual(rows[1]['metrics']['average_fps']['median'], 900)

    def test_duplicate_receipt_rejected(self):
        with self.assertRaises(ValueError):
            summarize([receipt(0, 100), receipt(0, 100)])


if __name__ == '__main__':
    unittest.main()
