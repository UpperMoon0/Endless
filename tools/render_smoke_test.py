#!/usr/bin/env python3
"""Run a fresh-world native optional-renderer framebuffer test under Loom/Xvfb."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
SHOTS = ('fixture--80', 'fixture-512', 'fixture-1000000', 'fixture-nether', 'fixture-dimension-return', 'fixture-return',
         'fixture-reload', '12-fixture-edit')


def prepare(instance: Path, output: Path, shots=SHOTS, shader_pack: Path | None = None,
            shader_config: str = 'iris.properties') -> dict:
    """Use only generated scenes in a uniquely named world; never delete saves."""
    game = instance / '.minecraft'
    (game / 'config').mkdir(parents=True, exist_ok=True)
    (game / 'config' / 'endless.json').write_text(json.dumps({
        'buildHeight': {'minBuildHeight': -8_000_000, 'maxBuildHeight': 8_000_000},
    }), encoding='utf-8')
    subprocess.run([
        sys.executable, str(ROOT / 'tools/capture_shader_preview.py'),
        '--instance', str(instance), '--output', str(output), '--fixture-only',
        '--create-world', '--world', 'Endless Render Smoke ' + uuid.uuid4().hex,
        '--shots', ','.join(shots),
    ], check=True, cwd=ROOT)
    path = game / 'endless-preview-request.json'
    request = json.loads(path.read_text())
    # Software rendering in CI: a small framebuffer and short settlement gate.
    # Native readiness, snapshot, sort, lifecycle and lighting assertions remain.
    request.update(width=640, height=360, renderDistance=4,
                   warmupFrames=30, warmupSeconds=3)
    if shader_pack is not None:
        shader_pack = shader_pack.resolve(strict=True)
        packs = game / 'shaderpacks'
        packs.mkdir(exist_ok=True)
        shutil.copy2(shader_pack, packs / shader_pack.name)
        (game / 'config' / shader_config).write_text(
            'enableShaders=true\nshaderPack=' + shader_pack.name + '\n', encoding='utf-8')
        request['expectShaders'] = True
    else:
        # Each run owns this isolated profile. A preceding active-pack run must
        # not silently leave shaders enabled in the next disabled test.
        (game / 'config' / shader_config).write_text('enableShaders=false\n', encoding='utf-8')
    path.write_text(json.dumps(request, indent=2), encoding='utf-8')
    return request


def validate(output: Path, request: dict, oculus: bool, target: str = "forge-1.20.1", renderer: str = "embeddium") -> list[dict]:
    records = json.loads((output / 'capture-manifest.json').read_text())
    expected = {shot['name'] + '.png' for shot in request['shots']}
    if (output / 'failure.txt').exists():
        raise RuntimeError((output / 'failure.txt').read_text())
    if len(records) != len(expected) or {r['file'] for r in records} != expected:
        raise RuntimeError('Missing or duplicate native framebuffer captures')
    for record in records:
        active = bool(request.get('expectShaders', False))
        if not record[renderer] or record['oculus'] != oculus or record['shadersActive'] != active:
            raise RuntimeError('Unexpected renderer/Oculus/shader state')
        if active and 'Complementary' not in record.get('shaderPack', ''):
            raise RuntimeError('Expected active Complementary pipeline')
        if active and not record.get('shaderPipeline', '').endswith('.IrisRenderingPipeline'):
            raise RuntimeError('Expected actual Iris shader pipeline')
        if (record['width'], record['height']) != (640, 360) or record['sampledColors'] < 16 or (not active and record.get('markerPixels', 0) < 1):
            raise RuntimeError('Wrong framebuffer dimensions or blank frame')
        if record['frameTimeP95Ms'] <= 0 or record['renderThreadAllocatedBytes'] < -1:
            raise RuntimeError('Missing frame-time/allocation measurements')
        if not (output / record['file']).is_file():
            raise RuntimeError('Manifest references a missing framebuffer')
        if record['file'] in ('fixture-nether.png', 'fixture-dimension-return.png', 'fixture-reload.png') and record['managerLifecycle'] != 'old manager/cache replaced; workers stopped; GPU resources released':
            raise RuntimeError('Manager lifecycle receipt missing')
        if record['file'] == 'fixture-512.png':
            workload = record.get('denseEditWorkload', {})
            if (workload.get('frames') != 120 or workload.get('edits') != 7680
                or workload.get('dirtyNotifications') != 130680
                or workload.get('frameTimeP95Ms', 0) <= 0
                or workload.get('renderThreadAllocatedBytes', -2) < -1):
                raise RuntimeError('Sustained dense-edit frame measurements missing')
        if record['file'] != '12-fixture-edit.png':
            checks = record.get('compatibilityRegressions', '')
            sky = record['dimension'] == 'minecraft:overworld'
            required = ['complete mesh output', 'unload/cancel/late upload' if target == 'forge-1.20.1' else 'chunk unload/reload',
                        'dense roof edits' if sky else 'no-skylight dimension skip']
            if sky and record['fixtureY'] >= 320:
                required += ['distant sky page/removal', 'snapshot halo']
            if any(check not in checks for check in required):
                raise RuntimeError('Native regression receipt missing')
            measurements = record.get('nativeRegressionMeasurements', {})
            if target != 'forge-1.20.1' and measurements.get('completeMeshBytes', 0) <= 0:
                raise RuntimeError('Complete native mesh measurements missing')
            if target != 'forge-1.20.1' and (measurements.get('activeMeshCancellationPolls', 0) < 2 or not measurements.get('lateUploadFiltered')):
                raise RuntimeError('Active mesh cancellation/late upload measurements missing')
            if target != 'forge-1.20.1' and renderer == 'embeddium' and not measurements.get('nativeSortPrecision'):
                raise RuntimeError('Native transparent sort regression receipt missing')
            if target == 'neoforge-26.1.2' and measurements.get('queuedMeshHeight') != record['fixtureY'] // 16:
                raise RuntimeError('Native mesh queue height receipt missing')
            if target == 'neoforge-26.1.2' and not measurements.get('logicalBoundsWithDenseCore'):
                raise RuntimeError('Logical world bounds/dense core receipt missing')
            names = ['completeMeshing', 'chunkLifecycle'] if target == 'forge-1.20.1' else []
            if target == 'forge-1.20.1' and not measurements.get('globalRendererLifecycle'):
                raise RuntimeError('Global renderer lifecycle regression receipt missing')
            if target == 'forge-1.20.1' and not measurements.get('offThreadRebuild'):
                raise RuntimeError('Off-thread rebuild regression receipt missing')
            if sky and target == 'forge-1.20.1':
                names += ['denseRoof']
                if record['fixtureY'] >= 320:
                    names += ['skyPageBurst']
            if sky:
                if (measurements.get('denseBurstFrames') != 120
                    or measurements.get('denseBurstEdits') != 7680
                    or measurements.get('denseBurstNotifications') != 130560
                    or measurements.get('denseBurstRefreshedColumns') != 1080):
                    raise RuntimeError('Dense burst batching receipt missing')
                if record['fixtureY'] == -80:
                    exposed = measurements.get('denseRoofExposedSky', -1)
                    inserted = measurements.get('denseRoofInsertedSky', -1)
                    if not (0 <= inserted < exposed <= 15) or measurements.get('denseRoofRemovedSky') != exposed:
                        raise RuntimeError('Dense roof lighting values missing or stale')
            if any(measurements.get(name + 'Ms', -1) < 0
                   or measurements.get(name + 'RenderThreadBytes', -2) < -1 for name in names):
                raise RuntimeError('Native probe measurements missing')
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=('forge-1.20.1','fabric-1.20.1','fabric-1.21.1','neoforge-1.21.1','neoforge-26.1.2'), default='forge-1.20.1')
    parser.add_argument('--renderer', choices=('embeddium','sodium'), default='embeddium')
    parser.add_argument("--shots", default=",".join(SHOTS))
    parser.add_argument("--iris", action="store_true")
    parser.add_argument("--sodium-pin")
    parser.add_argument('--shader-pack', type=Path, help='Enable a local Complementary ZIP with Iris/Oculus')
    parser.add_argument('--oculus', action='store_true', help='Load pinned Oculus with shaders disabled')
    parser.add_argument('--timeout', type=int, default=1200)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    if args.shader_pack and not (args.iris or args.oculus):
        parser.error('--shader-pack requires --iris or --oculus')
    backend = 'oculus' if args.oculus else ('iris' if args.iris else args.renderer)
    output = ROOT / 'build/render-smoke' / (args.target + '-' + backend + '-' + uuid.uuid4().hex)
    output.mkdir(parents=True)
    request = prepare(ROOT / args.target / 'run/shader-preview', output, args.shots.split(','), args.shader_pack,
                      'oculus.properties' if args.oculus else 'iris.properties')
    if args.prepare_only:
        print(output)
        return
    command = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
               ':' + args.target + ':runShaderPreviewClient', '-PshaderPreview',
               '--no-daemon', '--stacktrace', '--console=plain', '-Dorg.gradle.jvmargs=-Xmx1536m', '-Dorg.gradle.workers.max=2']
    if args.oculus:
        command.append('-PpreviewOculus')
    if args.target != 'forge-1.20.1':
        command.append('-PpreviewRenderer=' + args.renderer)
    if args.iris:
        command.append('-PpreviewIris')
    if args.sodium_pin:
        command.append('-PpreviewSodiumPin=' + args.sodium_pin)
        if args.target == 'fabric-1.21.1' and args.sodium_pin == 'SMxNOGZ6':
            # Loom's supported opt-out only affects dependency build-version
            # validation. Keep the upstream artifact intact and exercise its
            # actual static mixins in the native client before accepting it.
            command.append('-Ploom.ignoreDependencyLoomVersionValidation=true')
    if sys.platform.startswith('linux'):
        command = ['xvfb-run', '-a', '-s', '-screen 0 1280x720x24', *command]
    env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE='1')
    with (output / 'client.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                   stderr=subprocess.STDOUT,
                                   start_new_session=os.name != 'nt')
        deadline = time.monotonic() + args.timeout
        try:
            while process.poll() is None:
                if (output / 'failure.txt').exists():
                    raise RuntimeError((output / 'failure.txt').read_text())
                if time.monotonic() > deadline:
                    raise RuntimeError('Render smoke timeout; inspect client.log/progress.json')
                time.sleep(1)
            if process.returncode:
                raise RuntimeError(f'Render client/build exited {process.returncode}; inspect {output}/client.log')
            records = validate(output, request, args.oculus, args.target, args.renderer)
            if any(bool(r.get('iris')) != args.iris for r in records):
                raise RuntimeError('Unexpected Iris loader state')
            head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
            dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
            (output / 'pass.json').write_text(json.dumps({
                'head': head, 'dirty': dirty, 'backend': backend, 'captures': len(records),
                'shaderScope': ('Complementary active' if args.shader_pack else
                    ('Iris loaded with shaders disabled' if args.iris else
                     ('Oculus loaded with shaders disabled' if args.oculus else 'Shader loader absent'))),
            }, indent=2))
            print(f'ENDLESS_RENDER_SMOKE_PASS {head} {backend}: {len(records)} captures ({output})')
        finally:
            if process.poll() is None:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], check=False)
                else:
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    if os.name != 'nt':
                        os.killpg(process.pid, signal.SIGKILL)


if __name__ == '__main__':
    main()
