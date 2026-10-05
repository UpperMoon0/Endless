"""Validate optional renderer hooks and Minecraft selectors in the shipped Forge jar."""
import argparse
import json
from pathlib import Path
import zipfile

PREFIX = 'com/nstut/endless/forge/mixin/compat/embeddium/'
MIXINS = {
    'ClonedChunkSectionCacheMixin', 'WorldSliceMixin', 'ClonedChunkSectionMixin',
    'RenderSectionManagerMixin', 'OcclusionCullerMixin', 'ChunkBuilderMeshingTaskMixin',
    'ChunkBuilderSortTaskMixin', 'SodiumWorldRendererMixin', 'VerticalClientUpdatesMixin',
}
EXPECTED = {
    'SodiumWorldRendererMixin': {
        'Lnet/minecraft/core/BlockPos;asLong()J': 'Lnet/minecraft/core/BlockPos;m_121878_()J',
    },
    'OcclusionCullerMixin': {
        'Lnet/minecraft/world/level/Level;getMinSection()I': 'Lnet/minecraft/world/level/Level;m_151560_()I',
        'Lnet/minecraft/world/level/Level;getMaxSection()I': 'Lnet/minecraft/world/level/Level;m_151561_()I',
    },
    'WorldSliceMixin': {
        'Lnet/minecraft/world/level/Level;getSectionIndexFromSectionY(I)I': 'Lnet/minecraft/world/level/Level;m_151566_(I)I',
        'Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;': 'Lnet/minecraft/world/level/chunk/LevelChunk;m_7103_()[Lnet/minecraft/world/level/chunk/LevelChunkSection;',
    },
    'RenderSectionManagerMixin': {
        'Lnet/minecraft/client/multiplayer/ClientLevel;getSectionIndexFromSectionY(I)I': 'Lnet/minecraft/client/multiplayer/ClientLevel;m_151566_(I)I',
        'Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;': 'Lnet/minecraft/world/level/chunk/ChunkAccess;m_7103_()[Lnet/minecraft/world/level/chunk/LevelChunkSection;',
    },
}


def check_jar(path: Path) -> list[str]:
    errors = []
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        config = json.loads(jar.read('endless-embeddium.mixins.json'))
        if set(config.get('client', [])) != MIXINS or config.get('mixins') or config.get('server'):
            errors.append('Renderer hooks must be complete and client-only')
        if config.get('plugin') != 'com.nstut.endless.forge.compat.EmbeddiumMixinPlugin':
            errors.append('Optional renderer mod-ID gate missing')
        for mixin in MIXINS:
            if PREFIX + mixin + '.class' not in names:
                errors.append(f'Missing packaged hook: {mixin}')
        if any(name.startswith('me/jellysquid/mods/sodium/') or 'EmbeddiumShaderPreview' in name
               or 'EmbeddiumCompatibilityRegression' in name for name in names):
            errors.append('Normal jar bundles renderer or preview fixture')
        mappings = {}
        for name in names:
            if name.endswith('refmap.json'):
                mappings.update(json.loads(jar.read(name)).get('mappings', {}))
        for mixin, selectors in EXPECTED.items():
            for selector, runtime in selectors.items():
                if mappings.get(PREFIX + mixin, {}).get(selector) != runtime:
                    errors.append(f'{mixin}: missing runtime selector {runtime}')
    return errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    errors = check_jar(parser.parse_args().jar)
    print('\n'.join(errors) if errors else 'Packaged Embeddium hooks, runtime selectors and exclusions passed')
    raise SystemExit(bool(errors))
