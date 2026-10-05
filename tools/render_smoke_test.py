#!/usr/bin/env python3
"""Run a fresh-world native Embeddium framebuffer smoke test under Loom/Xvfb."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
SHOTS = ('fixture--80', 'fixture-512', 'fixture-1000000', 'fixture-nether', 'fixture-dimension-return', 'fixture-return',
         'fixture-reload', '12-fixture-edit')


def prepare(instance: Path, output: Path) -> dict:
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
        '--shots', ','.join(SHOTS),
    ], check=True, cwd=ROOT)
    path = game / 'endless-preview-request.json'
    request = json.loads(path.read_text())
    # Software rendering in CI: a small framebuffer and short settlement gate.
    # Native readiness, snapshot, sort, lifecycle and lighting assertions remain.
    request.update(width=640, height=360, renderDistance=4,
                   warmupFrames=30, warmupSeconds=3)
    path.write_text(json.dumps(request, indent=2), encoding='utf-8')
    return request


def validate(output: Path, request: dict, oculus: bool) -> list[dict]:
    records = json.loads((output / 'capture-manifest.json').read_text())
    expected = {shot['name'] + '.png' for shot in request['shots']}
    if (output / 'failure.txt').exists():
        raise RuntimeError((output / 'failure.txt').read_text())
    if len(records) != len(expected) or {r['file'] for r in records} != expected:
        raise RuntimeError('Missing or duplicate native framebuffer captures')
    for record in records:
        if not record['embeddium'] or record['oculus'] != oculus or record['shadersActive']:
            raise RuntimeError('Unexpected renderer/Oculus/shader state')
        if (record['width'], record['height']) != (640, 360) or record['sampledColors'] < 16 or record.get('markerPixels', 0) < 1:
            raise RuntimeError('Wrong framebuffer dimensions or blank frame')
        if record['frameTimeP95Ms'] <= 0 or record['renderThreadAllocatedBytes'] < -1:
            raise RuntimeError('Missing frame-time/allocation measurements')
        if not (output / record['file']).is_file():
            raise RuntimeError('Manifest references a missing framebuffer')
        if record['file'] in ('fixture-nether.png', 'fixture-dimension-return.png', 'fixture-reload.png') and record['managerLifecycle'] != 'old manager/cache replaced; workers stopped; GPU resources released':
            raise RuntimeError('Manager lifecycle receipt missing')
        if record['file'] != '12-fixture-edit.png':
            checks = record.get('compatibilityRegressions', '')
            sky = record['dimension'] == 'minecraft:overworld'
            required = ['complete mesh output', 'unload/cancel/late upload',
                        'dense roof edits' if sky else 'no-skylight dimension skip']
            if sky and record['fixtureY'] >= 320:
                required += ['distant sky page/removal', 'snapshot halo']
            if any(check not in checks for check in required):
                raise RuntimeError('Native regression receipt missing')
            measurements = record.get('nativeRegressionMeasurements', {})
            names = ['completeMeshing', 'chunkLifecycle']
            if sky:
                names += ['denseRoof']
                if record['fixtureY'] >= 320:
                    names += ['skyPageBurst']
            if any(measurements.get(name + 'Ms', -1) < 0
                   or measurements.get(name + 'RenderThreadBytes', -2) < -1 for name in names):
                raise RuntimeError('Native probe measurements missing')
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--oculus', action='store_true', help='Load pinned Oculus with shaders disabled')
    parser.add_argument('--timeout', type=int, default=1200)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    backend = 'oculus' if args.oculus else 'embeddium'
    output = ROOT / 'build/render-smoke' / (backend + '-' + uuid.uuid4().hex)
    output.mkdir(parents=True)
    request = prepare(ROOT / 'forge-1.20.1/run/shader-preview', output)
    if args.prepare_only:
        print(output)
        return
    command = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
               ':forge-1.20.1:runShaderPreviewClient', '-PshaderPreview',
               '--no-daemon', '--stacktrace', '--console=plain']
    if args.oculus:
        command.append('-PpreviewOculus')
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
            records = validate(output, request, args.oculus)
            head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
            dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
            (output / 'pass.json').write_text(json.dumps({
                'head': head, 'dirty': dirty, 'backend': backend, 'captures': len(records),
                'shaderScope': 'Oculus loaded with shaders disabled' if args.oculus else 'Oculus absent',
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
