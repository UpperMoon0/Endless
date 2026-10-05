import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import render_smoke_test as smoke


class RenderSmokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.request = {'shots': [{'name': 'fixture-1000000'}]}
        self.record = dict(file='fixture-1000000.png', fixtureY=1_000_000,
                           embeddium=True, oculus=False, shadersActive=False, dimension='minecraft:overworld',
                           width=640, height=360, sampledColors=100, markerPixels=30,
                           frameTimeP95Ms=12, renderThreadAllocatedBytes=1024,
                           compatibilityRegressions='dense roof edits, complete mesh output, '
                           'unload/cancel/late upload, distant sky page/removal, snapshot halo')
        self.record['nativeRegressionMeasurements'] = {name + suffix: 10
            for name in ('completeMeshing', 'chunkLifecycle', 'denseRoof', 'skyPageBurst')
            for suffix in ('Ms', 'RenderThreadBytes')}
        (self.root / self.record['file']).write_bytes(b'fixture')

    def write(self, records=None):
        (self.root / 'capture-manifest.json').write_text(json.dumps(
            records if records is not None else [self.record]))

    def test_complete_receipt(self):
        self.write()
        self.assertEqual(1, len(smoke.validate(self.root, self.request, False)))

    def test_requires_edge_and_dense_regressions(self):
        self.record['compatibilityRegressions'] = 'complete mesh output'
        self.write()
        with self.assertRaisesRegex(RuntimeError, 'regression receipt'):
            smoke.validate(self.root, self.request, False)

    def test_rejects_wrong_renderer_and_blank_frame(self):
        for changes in (dict(oculus=True), dict(embeddium=False), dict(shadersActive=True), dict(sampledColors=1), dict(markerPixels=0)):
            saved = self.record.copy()
            self.record.update(changes)
            self.write()
            with self.assertRaises(RuntimeError):
                smoke.validate(self.root, self.request, False)
            self.record = saved

    def test_requires_complete_unique_captures(self):
        for records in ([], [self.record, self.record]):
            self.write(records)
            with self.assertRaisesRegex(RuntimeError, 'Missing or duplicate'):
                smoke.validate(self.root, self.request, False)

    def test_requires_native_allocation_and_burst_measurements(self):
        self.record['nativeRegressionMeasurements'].pop('skyPageBurstRenderThreadBytes')
        self.write()
        with self.assertRaisesRegex(RuntimeError, 'measurements missing'):
            smoke.validate(self.root, self.request, False)

    def test_dimension_transition_requires_shutdown_receipt(self):
        self.record.update(file='fixture-nether.png', dimension='minecraft:the_nether', fixtureY=512,
                           managerLifecycle='not requested', compatibilityRegressions=
                           'complete mesh output, unload/cancel/late upload, no-skylight dimension skip')
        self.request = {'shots': [{'name': 'fixture-nether'}]}
        (self.root / self.record['file']).write_bytes(b'fixture')
        self.write()
        with self.assertRaisesRegex(RuntimeError, 'lifecycle receipt'):
            smoke.validate(self.root, self.request, False)
        self.record['managerLifecycle'] = 'old manager/cache replaced; workers stopped; GPU resources released'
        self.write()
        self.assertEqual(1, len(smoke.validate(self.root, self.request, False)))

    def test_fresh_mode_refuses_existing_world(self):
        instance = self.root / 'instance'
        save = instance / '.minecraft/saves/Preserve/level.dat'
        save.parent.mkdir(parents=True)
        save.write_bytes(b'keep')
        result = subprocess.run([
            sys.executable, str(smoke.ROOT / 'tools/capture_shader_preview.py'),
            '--instance', str(instance), '--output', str(self.root / 'out'),
            '--world', 'Preserve', '--fixture-only', '--create-world',
        ], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('already exists', result.stderr)
        self.assertEqual(b'keep', save.read_bytes())

    def test_prepare_uses_generated_world_and_preserves_saved_worlds(self):
        instance = self.root / 'instance'
        save = instance / '.minecraft/saves/Keep This World/level.dat'
        save.parent.mkdir(parents=True)
        save.write_bytes(b'preserved')
        request = smoke.prepare(instance, self.root / 'evidence')
        self.assertTrue(request['createWorld'])
        self.assertEqual(set(smoke.SHOTS), {shot['name'] for shot in request['shots']})
        self.assertFalse(any('tower' in shot['name'] for shot in request['shots']))
        self.assertEqual(b'preserved', save.read_bytes())
        self.assertEqual(640, request['width'])
        self.assertEqual({'minBuildHeight': -8_000_000, 'maxBuildHeight': 8_000_000},
                         json.loads((instance / '.minecraft/config/endless.json').read_text())['buildHeight'])


if __name__ == '__main__':
    unittest.main()
