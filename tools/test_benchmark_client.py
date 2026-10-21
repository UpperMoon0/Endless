import unittest
import csv
from pathlib import Path
import shutil
import subprocess
import tempfile
from unittest import mock
from benchmark_client import allowed, library_path, summarize_frames, phase, wait_tall_ready


class ClientBenchmarkTests(unittest.TestCase):
    def tall_readiness(self, directory, rendering):
        (directory/'scene-2000.txt').write_text('neutralPixels=100000\nyellowPixels=100')
        with mock.patch('benchmark_client.phase'), mock.patch('benchmark_client.wait_phase'), \
                mock.patch('benchmark_client.time.sleep'), \
                mock.patch('benchmark_client.time.monotonic', side_effect=range(100)):
            wait_tall_ready(directory, mock.Mock(), rendering, 2048, 20)

    def test_lod_readiness_requires_a_complete_queue_receipt(self):
        for receipt in (None, '{}', '{cachedSections=100}', '{pendingVisible=unknown}',
                        '{pendingVisible=-1}', '{pendingVisible=0.5}', '{pendingVisible=0unknown}'):
            with self.subTest(receipt=receipt), tempfile.TemporaryDirectory() as temporary:
                directory=Path(temporary)
                if receipt is not None:
                    (directory/'lod-2000.txt').write_text(receipt)
                with self.assertRaisesRegex(RuntimeError, 'LOD queue observer'):
                    self.tall_readiness(directory, {'verticalRenderSections':32, 'verticalLodSections':256})

    def test_pending_lod_builds_cannot_pass_readiness(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)
            (directory/'lod-2000.txt').write_text('{pendingVisible=1}')
            with self.assertRaisesRegex(RuntimeError, 'did not settle'):
                self.tall_readiness(directory, {'verticalRenderSections':32, 'verticalLodSections':256})

    def test_idle_lod_and_native_only_controls_can_settle(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary)
            self.tall_readiness(directory, {'verticalRenderSections':256, 'verticalLodSections':0})
            (directory/'lod-2000.txt').write_text('{pendingVisible=0}')
            self.tall_readiness(directory, {'verticalRenderSections':32, 'verticalLodSections':256})

    def test_transient_reader_lock_preserves_atomic_phase_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);live=directory/'phase.txt';live.write_text('0')
            original=Path.replace;attempts=[]
            def locked_then_publish(path,target):
                attempts.append(1)
                self.assertEqual('0',live.read_text())
                if len(attempts)<3: raise PermissionError('Windows reader lock')
                return original(path,target)
            with mock.patch.object(Path,'replace',autospec=True,side_effect=locked_then_publish), mock.patch('benchmark_client.time.sleep'):
                phase(directory,3)
            self.assertEqual(3,len(attempts));self.assertEqual('3',live.read_text())
            self.assertFalse((directory/'phase.tmp').exists())

    def test_persistent_reader_lock_fails_without_corrupting_live_phase(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory=Path(temporary);live=directory/'phase.txt';live.write_text('0')
            with mock.patch.object(Path,'replace',side_effect=PermissionError('locked')) as replace, mock.patch('benchmark_client.time.sleep'):
                with self.assertRaises(PermissionError): phase(directory,3)
            self.assertEqual(100,replace.call_count);self.assertEqual('0',live.read_text())

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
 static void awaitPhase(int expected) throws Exception {
  var phase=FrameMetrics.class.getDeclaredField("phase");
  phase.setAccessible(true);
  long deadline=System.nanoTime()+3_000_000_000L;
  while (phase.getInt(null)!=expected) {
   if (System.nanoTime()>deadline) throw new AssertionError("controller did not observe phase "+expected);
   Thread.sleep(5);
  }
 }
 public static void main(String[] args) throws Exception {
  Path out=Path.of(args[0]);
  Files.writeString(out.resolve("phase.txt"),"1");
  FrameMetrics.start(out);
  awaitPhase(1);
  FrameMetrics.present();
  Thread.sleep(2); FrameMetrics.present();
  Thread.sleep(2); FrameMetrics.present();
  Files.writeString(out.resolve("phase.txt"),"0");
  Thread.sleep(150); awaitPhase(0); FrameMetrics.present();
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
