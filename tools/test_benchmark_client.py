import unittest
import csv
from pathlib import Path
import shutil
import subprocess
import tempfile
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

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_present_window_retains_stall_at_stop_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            harness = directory / 'FrameHarness.java'
            harness.write_text('''import endless.bench.FrameMetrics;
import java.nio.file.*;
public class FrameHarness {
 public static void main(String[] args) throws Exception {
  Path out=Path.of(args[0]);
  Files.writeString(out.resolve("phase.txt"),"1");
  FrameMetrics.start(out);
  Thread.sleep(150);
  FrameMetrics.present();
  Thread.sleep(2); FrameMetrics.present();
  Thread.sleep(2); FrameMetrics.present();
  Files.writeString(out.resolve("phase.txt"),"0");
  Thread.sleep(150); FrameMetrics.present();
  Files.writeString(out.resolve("phase.txt"),"-1");
  Thread.sleep(5000);
  throw new AssertionError("controller did not exit");
 }
}''')
            source = Path(__file__).parent / 'performance/FrameMetrics.java'
            subprocess.run(['javac', '-d', str(directory), str(source), str(harness)], check=True, capture_output=True, timeout=30)
            subprocess.run(['java', '-cp', str(directory), 'FrameHarness', str(directory)], check=True, capture_output=True, timeout=15)
            with (directory / 'frames.csv').open() as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual([row['phase'] for row in rows], ['1', '1', '1'])
            self.assertGreater(int(rows[-1]['frame_ns']), 100_000_000)


if __name__ == '__main__':
    unittest.main()
