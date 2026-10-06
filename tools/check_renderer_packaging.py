"""Audit the shipped optional renderer hooks without loading renderer classes."""
import argparse
import json
import re
from pathlib import Path
import zipfile


def check_jar(path: Path) -> list[str]:
    errors = []
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        configs = sorted(n for n in names if n in ('endless-embeddium.mixins.json', 'endless-sodium.mixins.json'))
        if not configs:
            errors.append('Missing optional renderer mixin configuration')
        metadata = jar.read('fabric.mod.json' if 'fabric.mod.json' in names else 'META-INF/neoforge.mods.toml').decode()
        mappings = {}
        for name in names:
            if name.endswith('refmap.json'):
                mappings.update(json.loads(jar.read(name)).get('mappings', {}))
        for name in configs:
            config = json.loads(jar.read(name))
            if name not in metadata or not config.get('client') or config.get('mixins') or config.get('server'):
                errors.append(f'{name}: renderer hooks must be registered and client-only')
            plugin = config.get('plugin', '').replace('.', '/') + '.class'
            if plugin not in names:
                errors.append(f'{name}: missing optional mod gate')
            for mixin in config.get('client', []):
                member = config['package'].replace('.', '/') + '/' + mixin + '.class'
                if member not in names:
                    errors.append(f'{name}: missing hook {mixin}')
                    continue
                if 'fabric.mod.json' in names and mixin in ('WorldSliceMixin', 'RenderSectionManagerMixin', 'RenderSectionManagerModernMixin', 'OcclusionCullerMixin'):
                    bytecode = jar.read(member)
                    selectors = re.findall(rb'Lnet/minecraft/(?:world/level|client/multiplayer)/[A-Za-z0-9/$]+;[A-Za-z0-9_$]+\([^)]*\)(?:[VZBCSIJFD]|\[*L[A-Za-z0-9/$]+;|\[+[VZBCSIJFD])', bytecode)
                    refmap = mappings.get(member[:-6], {})
                    for selector in selectors:
                        runtime = refmap.get(selector.decode(), '')
                        if 'Lnet/minecraft/class_' not in runtime or 'method_' not in runtime:
                            errors.append(f'{mixin}: missing production refmap entry for {selector.decode()}')
                    if not selectors and b'Lnet/minecraft/class_' not in bytecode:
                        errors.append(f'{mixin}: missing intermediary Minecraft injection selectors')
        for name in names:
            if name.startswith(('me/jellysquid/mods/sodium/', 'net/caffeinemc/mods/sodium/', 'org/embeddedt/embeddium/')):
                errors.append('Renderer classes bundled in normal artifact')
                break
            if '/testing/renderer/' in name or '/testing/Embeddium' in name:
                errors.append('Development fixture bundled in normal artifact')
                break
    return errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jars', type=Path, nargs='+')
    failures = []
    for path in parser.parse_args().jars:
        failures.extend(f'{path.name}: {message}' for message in check_jar(path))
    print('\n'.join(failures) if failures else 'Optional renderer registration, production selectors and exclusions passed')
    raise SystemExit(bool(failures))
