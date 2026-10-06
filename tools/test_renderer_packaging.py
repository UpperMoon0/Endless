import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from check_renderer_packaging import check_jar, REQUIRED_HOOKS


class RendererPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / 'release.jar'
        self.hook = 'com/example/WorldSliceMixin'
        self.selector = 'Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;'
        self.files = {
            'fabric.mod.json': json.dumps({'mixins': ['endless-sodium.mixins.json'], 'depends': {'minecraft': '=1.21.1'}}),
            'endless-sodium.mixins.json': json.dumps({'package': 'com.example', 'client': ['WorldSliceMixin'], 'plugin': 'com.example.Gate', 'refmap': 'release-refmap.json'}),
            'com/example/Gate.class': b'gate',
            self.hook + '.class': self.selector.encode(),
            'release-refmap.json': json.dumps({'mappings': {self.hook: {self.selector: 'Lnet/minecraft/class_2818;method_12006()[Lnet/minecraft/class_2826;'}}}),
        }
        self.configure_target('fabric', '1.21.1')

    def configure_target(self, loader, version):
        self.files = {name: data for name, data in self.files.items()
                      if name == 'release-refmap.json' or name == self.hook + '.class'}
        configs = REQUIRED_HOOKS[loader, version]
        if loader == 'fabric':
            self.files['fabric.mod.json'] = json.dumps({'mixins': list(configs), 'depends': {'minecraft': '=' + version}})
        else:
            metadata = 'META-INF/mods.toml' if loader == 'forge' else 'META-INF/neoforge.mods.toml'
            self.files[metadata] = ('[[dependencies.endless]]\nmodId = "minecraft"\nversionRange = "[' + version + ',)"\n' +
                                    ''.join('[[mixins]]\nconfig = "' + name + '"\n' for name in configs))
            if loader == 'forge':
                self.files['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\nMixinConfigs: ' + ','.join(configs) + '\r\n'
        self.files['com/example/Gate.class'] = b'gate'
        for name, hooks in configs.items():
            self.files[name] = json.dumps({'package': 'com.example', 'client': sorted(hooks),
                'plugin': 'com.example.Gate', 'refmap': 'release-refmap.json'})
            for hook in hooks:
                self.files.setdefault('com/example/' + hook + '.class', b'Lnet/minecraft/class_2818;')

    def check(self):
        with zipfile.ZipFile(self.jar, 'w') as jar:
            for name, data in self.files.items():
                jar.writestr(name, data)
        return check_jar(self.jar)

    def test_production_refmap_satisfies_named_optional_hook(self):
        self.assertEqual([], self.check())

    def test_every_release_target_accepts_complete_contract(self):
        for loader, version in REQUIRED_HOOKS:
            with self.subTest(loader=loader, version=version):
                self.configure_target(loader, version)
                self.assertEqual([], self.check())

    def test_omitting_configuration_entry_and_class_is_rejected_per_target(self):
        for loader, version in REQUIRED_HOOKS:
            for name, hooks in REQUIRED_HOOKS[loader, version].items():
                for hook in hooks:
                    with self.subTest(loader=loader, version=version, config=name, hook=hook):
                        self.configure_target(loader, version)
                        config = json.loads(self.files[name])
                        config['client'].remove(hook)
                        self.files[name] = json.dumps(config)
                        self.files.pop('com/example/' + hook + '.class')
                        self.assertIn(name + ': missing required hook ' + hook, self.check())

    def test_omitting_whole_configuration_is_rejected_per_target(self):
        for loader, version in REQUIRED_HOOKS:
            for name in REQUIRED_HOOKS[loader, version]:
                with self.subTest(loader=loader, version=version, config=name):
                    self.configure_target(loader, version)
                    self.files.pop(name)
                    self.assertTrue(any('missing required configuration ' + name in error for error in self.check()))

    def test_missing_class_of_registered_hook_is_rejected(self):
        self.files.pop('com/example/VerticalClientUpdatesMixin.class')
        self.assertTrue(any('missing hook VerticalClientUpdatesMixin' in error for error in self.check()))

    def test_unregistered_configuration_is_rejected_per_loader(self):
        for loader, version in REQUIRED_HOOKS:
            with self.subTest(loader=loader, version=version):
                self.configure_target(loader, version)
                if loader == 'fabric':
                    self.files['fabric.mod.json'] = json.dumps({'depends': {'minecraft': '=' + version}})
                elif loader == 'forge':
                    self.files['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\n'
                else:
                    self.files['META-INF/neoforge.mods.toml'] = '[[dependencies.endless]]\nmodId = "minecraft"\nversionRange = "[' + version + ',)"\n'
                self.assertTrue(any('registered and client-only' in error for error in self.check()))

    def test_forge_manifest_continuation_registers_renderer_config(self):
        self.configure_target('forge', '1.20.1')
        self.files['META-INF/MANIFEST.MF'] = 'MixinConfigs: endless-embe\r\n ddium.mixins.json\r\n'
        self.assertEqual([], self.check())

    def test_missing_production_mapping_is_rejected(self):
        self.files.pop('release-refmap.json')
        self.assertTrue(any('production refmap' in e for e in self.check()))

    def test_unregistered_production_mapping_is_rejected(self):
        config = json.loads(self.files['endless-sodium.mixins.json'])
        for refmap in (None, 'missing-refmap.json'):
            config['refmap'] = refmap
            self.files['endless-sodium.mixins.json'] = json.dumps(config)
            self.assertTrue(any('registered production refmap' in e for e in self.check()))

    def test_server_registration_is_rejected(self):
        self.files['endless-sodium.mixins.json'] = json.dumps({'package': 'com.example', 'client': ['WorldSliceMixin'], 'server': ['WorldSliceMixin'], 'plugin': 'com.example.Gate'})
        self.assertTrue(any('client-only' in e for e in self.check()))

    def test_bundled_renderer_and_fixture_are_rejected(self):
        for name in ('net/caffeinemc/mods/sodium/Library.class', 'net/irisshaders/iris/Iris.class',
                     'com/example/testing/renderer/NativeRenderer.class'):
            self.files[name] = b'forbidden'
            self.assertTrue(any('bundled' in e for e in self.check()))
            self.files.pop(name)


if __name__ == '__main__':
    unittest.main()
