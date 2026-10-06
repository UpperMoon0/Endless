import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from check_renderer_packaging import check_jar


class RendererPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / 'release.jar'
        self.hook = 'com/example/WorldSliceMixin'
        self.selector = 'Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;'
        self.files = {
            'fabric.mod.json': json.dumps({'mixins': ['endless-sodium.mixins.json']}),
            'endless-sodium.mixins.json': json.dumps({'package': 'com.example', 'client': ['WorldSliceMixin'], 'plugin': 'com.example.Gate'}),
            'com/example/Gate.class': b'gate',
            self.hook + '.class': self.selector.encode(),
            'release-refmap.json': json.dumps({'mappings': {self.hook: {self.selector: 'Lnet/minecraft/class_2818;method_12006()[Lnet/minecraft/class_2826;'}}}),
        }

    def check(self):
        with zipfile.ZipFile(self.jar, 'w') as jar:
            for name, data in self.files.items():
                jar.writestr(name, data)
        return check_jar(self.jar)

    def test_production_refmap_satisfies_named_optional_hook(self):
        self.assertEqual([], self.check())

    def test_missing_production_mapping_is_rejected(self):
        self.files.pop('release-refmap.json')
        self.assertTrue(any('production refmap' in e for e in self.check()))

    def test_server_registration_is_rejected(self):
        self.files['endless-sodium.mixins.json'] = json.dumps({'package': 'com.example', 'client': ['WorldSliceMixin'], 'server': ['WorldSliceMixin'], 'plugin': 'com.example.Gate'})
        self.assertTrue(any('client-only' in e for e in self.check()))

    def test_bundled_renderer_and_fixture_are_rejected(self):
        for name in ('net/caffeinemc/mods/sodium/Library.class', 'com/example/testing/renderer/NativeRenderer.class'):
            self.files[name] = b'forbidden'
            self.assertTrue(any('bundled' in e for e in self.check()))
            self.files.pop(name)


if __name__ == '__main__':
    unittest.main()
