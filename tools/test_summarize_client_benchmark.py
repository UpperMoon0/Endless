import copy
import math
import unittest
from benchmark_client import planned_runs
from summarize_client_benchmark import summarize


def receipt(repeat, value, sparse=False):
    return {'label': 'current', 'sparse': sparse, 'repeat': repeat,
            'metrics': {workload: {'frames': int(value * 20), 'seconds': 20,
                'average_fps': value, 'one_percent_low_fps': value / 2,
                'median_ms': 1000 / value, 'p95_ms': 1500 / value,
                'p99_ms': 2000 / value, 'max_ms': 3000 / value}
                for workload in ('static', 'orbit', 'edits')}}


def metadata(rows):
    return {'measured_seconds': 20, 'planned_runs': [
        {key: row[key] for key in ('label', 'sparse', 'repeat')} for row in rows]}


class IndependentClientJvmTests(unittest.TestCase):
    def test_frame_counts_do_not_weight_jvms_and_heights_stay_separate(self):
        results = [receipt(0, 100), receipt(1, 300), receipt(0, 900, True)]
        rows = summarize(results, metadata(results))
        self.assertEqual(rows[0]['metrics']['average_fps'], {'median': 200, 'min': 100, 'max': 300})
        self.assertEqual(rows[3]['metrics']['average_fps']['median'], 900)

    def test_duplicate_receipt_rejected(self):
        row = receipt(0, 100)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            summarize([row, row], metadata([row]))

    def test_interrupted_series_rejected(self):
        results = [receipt(0, 100), receipt(1, 300)]
        with self.assertRaisesRegex(ValueError, 'JVM matrix'):
            summarize(results[:1], metadata(results))

    def test_unexpected_variant_or_repeat_rejected(self):
        expected = [receipt(0, 100)]
        for field, value in [('label', 'candidate'), ('repeat', 1), ('sparse', True)]:
            result = copy.deepcopy(expected)
            result[0][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'JVM matrix'):
                summarize(result, metadata(expected))

    def test_missing_or_extra_workload_rejected(self):
        rows = [receipt(0, 100)]
        plan = metadata(rows)
        for workload in ('static', 'orbit', 'edits'):
            altered = copy.deepcopy(rows)
            del altered[0]['metrics'][workload]
            with self.subTest(workload=workload), self.assertRaisesRegex(ValueError, 'workload'):
                summarize(altered, plan)
        rows[0]['metrics']['extra'] = rows[0]['metrics']['edits']
        with self.assertRaisesRegex(ValueError, 'workload'):
            summarize(rows, plan)

    def test_nonfinite_and_nonpositive_metrics_rejected(self):
        for name in ('seconds', 'average_fps', 'one_percent_low_fps', 'median_ms', 'p95_ms', 'p99_ms', 'max_ms'):
            for value in (math.nan, math.inf, -math.inf, 0, -1, True):
                rows = [receipt(0, 100)]
                rows[0]['metrics']['edits'][name] = value
                with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                    summarize(rows, metadata(rows))

    def test_short_windows_and_inconsistent_fps_rejected(self):
        for name, value in [('frames', 199), ('frames', 2000.0), ('frames', True),
                            ('seconds', 15), ('average_fps', 101)]:
            rows = [receipt(0, 100)]
            rows[0]['metrics']['edits'][name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                summarize(rows, metadata(rows))

    def test_missing_or_duplicate_plan_rejected(self):
        row = receipt(0, 100)
        for plan in ({'measured_seconds': 20}, metadata([row, row]), metadata([])):
            with self.assertRaisesRegex(ValueError, 'planned JVM matrix'):
                summarize([row], plan)

    def test_full_dense_and_sparse_requested_plans(self):
        full = planned_runs(['vanilla', 'fabric', 'current', 'candidate'], 3)
        self.assertEqual(len(full), 18)
        self.assertEqual(len(planned_runs(['current', 'candidate'], 3, sparse_only=True)), 6)
        self.assertEqual(len(planned_runs(['vanilla', 'fabric', 'current', 'candidate'], 3, dense_only=True)), 12)
        rows = []
        for entry in full:
            row = receipt(entry['repeat'], 100, entry['sparse'])
            row['label'] = entry['label']
            rows.append(row)
        self.assertEqual(len(summarize(rows, {'measured_seconds': 20, 'planned_runs': full})), 18)


if __name__ == '__main__':
    unittest.main()
