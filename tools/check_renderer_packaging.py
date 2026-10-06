"""Audit the shipped optional renderer hooks without loading renderer classes."""
import argparse
import json
import re
from pathlib import Path
import zipfile
import tomllib

# Independent shipping contract: do not derive this from the jar being audited.
BASE_HOOKS = frozenset(('ClonedChunkSectionCacheMixin', 'ClonedChunkSectionMixin',
    'WorldSliceMixin', 'RenderSectionManagerMixin', 'OcclusionCullerMixin',
    'SodiumWorldRendererMixin', 'VerticalClientUpdatesMixin'))
EMBEDDIUM_HOOKS = BASE_HOOKS | {'ChunkBuilderMeshingTaskMixin', 'ChunkBuilderSortTaskMixin'}
SODIUM_1211_HOOKS = BASE_HOOKS | {'RenderSectionManagerModernMixin', 'RenderSectionPendingMixin'}
REQUIRED_HOOKS = {
    ('fabric', '1.20.1'): {'endless-embeddium.mixins.json': EMBEDDIUM_HOOKS | {'MinecraftFrameMixin', 'RenderSortCameraMixin'}},
    ('forge', '1.20.1'): {'endless-embeddium.mixins.json': EMBEDDIUM_HOOKS},
    ('fabric', '1.21.1'): {'endless-sodium.mixins.json': SODIUM_1211_HOOKS | {'MinecraftFrameMixin'}},
    ('neoforge', '1.21.1'): {'endless-embeddium.mixins.json': EMBEDDIUM_HOOKS, 'endless-sodium.mixins.json': SODIUM_1211_HOOKS},
    ('neoforge', '26.1.2'): {'endless-sodium.mixins.json': BASE_HOOKS | {'VanillaDestructionLookupMixin', 'TaskCollectingTreeMixin', 'DeferredTaskListMixin'}},
}


def release_target(names, metadata):
    if 'fabric.mod.json' in names:
        data = json.loads(metadata)
        loader = 'fabric'
        constraint = data.get('depends', {}).get('minecraft', '')
    else:
        loader = 'neoforge' if 'META-INF/neoforge.mods.toml' in names else 'forge'
        data = tomllib.loads(metadata)
        constraint = next((d.get('versionRange', '') for d in data.get('dependencies', {}).get('endless', [])
                           if d.get('modId') == 'minecraft'), '')
    match = re.search(r'(?<![\d.])(1\.20\.1|1\.21\.1|26\.1\.2)(?![\d.])', str(constraint))
    return loader, match.group(1) if match else None


def registered_configs(jar, loader, metadata):
    if loader == 'fabric':
        return {entry if isinstance(entry, str) else entry.get('config')
                for entry in json.loads(metadata).get('mixins', [])}
    if loader == 'neoforge':
        return {entry.get('config') for entry in tomllib.loads(metadata).get('mixins', [])}
    attributes, current = {}, None
    if 'META-INF/MANIFEST.MF' in jar.namelist():
        for line in jar.read('META-INF/MANIFEST.MF').decode().splitlines():
            if line.startswith(' ') and current:
                attributes[current] += line[1:]
            elif ': ' in line:
                current, value = line.split(': ', 1)
                attributes[current] = value
    return {name.strip() for name in attributes.get('MixinConfigs', '').split(',')}


def check_jar(path: Path) -> list[str]:
    errors = []
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        configs = sorted(n for n in names if n in ('endless-embeddium.mixins.json', 'endless-sodium.mixins.json'))
        if not configs:
            errors.append('Missing optional renderer mixin configuration')
        metadata_name = next((n for n in ('fabric.mod.json', 'META-INF/neoforge.mods.toml', 'META-INF/mods.toml') if n in names), None)
        if metadata_name is None:
            return ['Missing loader metadata']
        metadata = jar.read(metadata_name).decode()
        target = release_target(names, metadata)
        registered = registered_configs(jar, target[0], metadata)
        required = REQUIRED_HOOKS.get(target)
        if required is None:
            errors.append(f'Unknown release target {target}; no required-hook contract')
        else:
            for name, hooks in required.items():
                if name not in names:
                    errors.append(f'{target}: missing required configuration {name}')
                    continue
                config = json.loads(jar.read(name))
                for hook in sorted(hooks - set(config.get('client', []))):
                    errors.append(f'{name}: missing required hook {hook}')
        mappings = {}
        for name in names:
            if name.endswith('refmap.json'):
                mappings.update(json.loads(jar.read(name)).get('mappings', {}))
        for name in configs:
            config = json.loads(jar.read(name))
            if 'fabric.mod.json' in names and config.get('refmap') not in names:
                errors.append(f'{name}: missing registered production refmap')
            if name not in registered or not config.get('client') or config.get('mixins') or config.get('server'):
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
            if name.startswith(('me/jellysquid/mods/sodium/', 'net/caffeinemc/mods/sodium/', 'org/embeddedt/embeddium/',
                                'net/irisshaders/iris/', 'net/coderbot/iris/')):
                errors.append('Renderer or shader classes bundled in normal artifact')
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
