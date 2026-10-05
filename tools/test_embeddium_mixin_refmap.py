import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from check_embeddium_mixin_refmap import check_jar, EXPECTED, MIXINS, PREFIX


class EmbeddiumPackagingTest(unittest.TestCase):
    def check(self, *, omit_selector=False, server_hook=False, bundled_fixture=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.jar'
            config = dict(client=sorted(MIXINS), plugin='com.nstut.endless.forge.compat.EmbeddiumMixinPlugin')
            mappings = {PREFIX + key: dict(value) for key, value in EXPECTED.items()}
            if omit_selector:
                mappings.pop(PREFIX + 'SodiumWorldRendererMixin')
            if server_hook:
                config['mixins'] = ['SodiumWorldRendererMixin']
            with zipfile.ZipFile(path, 'w') as jar:
                jar.writestr('endless-embeddium.mixins.json', json.dumps(config))
                jar.writestr('fixture.refmap.json', json.dumps(dict(mappings=mappings)))
                for name in MIXINS:
                    jar.writestr(PREFIX + name + '.class', b'')
                if bundled_fixture:
                    jar.writestr('com/nstut/endless/forge/testing/EmbeddiumCompatibilityRegression.class', b'')
            return check_jar(path)

    def test_complete_optional_client_adapter_passes(self):
        self.assertEqual([], self.check())

    def test_development_only_crack_selector_is_rejected(self):
        self.assertTrue(any('runtime selector' in e for e in self.check(omit_selector=True)))

    def test_server_hook_and_bundled_fixture_are_rejected(self):
        self.assertTrue(self.check(server_hook=True))
        self.assertTrue(self.check(bundled_fixture=True))
