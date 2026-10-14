import unittest
from benchmark_client import allowed, library_path, summarize_frames


class ClientBenchmarkTests(unittest.TestCase):
    def test_average_is_frames_over_elapsed_time(self):
        result = summarize_frames([10_000_000, 30_000_000])
        self.assertEqual(result['average_fps'], 50)
        self.assertEqual(result['seconds'], .04)

    def test_one_percent_low_is_slowest_tail_mean(self):
        result = summarize_frames([1_000_000] * 198 + [10_000_000, 30_000_000])
        self.assertEqual(result['one_percent_low_fps'], 50)
        self.assertEqual(result['p99_ms'], 1)
        self.assertEqual(result['max_ms'], 30)

    def test_invalid_intervals_rejected(self):
        for values in ([], [0], [-1], [1, 0]):
            with self.assertRaises(ValueError):
                summarize_frames(values)

    def test_windows_native_rules(self):
        self.assertTrue(allowed({'rules': [{'action': 'allow', 'os': {'name': 'windows'}}]}))
        self.assertFalse(allowed({'rules': [{'action': 'allow', 'os': {'name': 'windows-arm64'}}]}))
        self.assertFalse(allowed({'rules': [{'action': 'allow'}, {'action': 'disallow', 'os': {'name': 'windows'}}]}))

    def test_maven_classifier(self):
        self.assertEqual(library_path('org.lwjgl:lwjgl:3.3.3:natives-windows').as_posix(),
                         'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar')


if __name__ == '__main__':
    unittest.main()
