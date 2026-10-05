#!/usr/bin/env python3
"""Request native render-thread captures in an isolated copy of a world.

Build :forge-1.20.1:build -PshaderPreview, install the shader-preview jar in
an isolated Prism instance. This writes an opt-in request; the fixture hides its own GLFW window and
captures the actual framebuffer without keyboard input or desktop focus.
"""
import argparse
import json
import os
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--instance', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--world', default='Endless Shader Preview')
parser.add_argument('--shaders', action='store_true')
parser.add_argument('--fixture-only', action='store_true', help='Use generated scenes, return/reload and edit shots; no tower required')
parser.add_argument('--create-world', action='store_true', help='Create a new vanilla-registry fixture world; reject existing folders')
parser.add_argument('--shots', help='Comma-separated shot names for a focused rerun')
parser.add_argument('--vanilla', action='store_true', help='Verify a baseline without Embeddium/Oculus')
parser.add_argument('--launcher', type=Path, help='Optionally launch Prism and wait for complete captures')
parser.add_argument('--timeout', type=int, default=1200)
args = parser.parse_args()
game = args.instance / '.minecraft'
if args.create_world and (game / 'saves' / args.world).exists():
    parser.error('Fresh fixture world already exists; use a new world name.')
if args.create_world and not args.fixture_only:
    parser.error('--create-world requires --fixture-only; generated worlds have no tower.')
if not args.create_world and not (game / 'saves' / args.world / 'level.dat').is_file():
    parser.error('Copy the world into the isolated instance before capturing.')
shots = [
    dict(name='01-tower-base', eye=[-78, 112, -39], target=[-114, 99, -81]),
    dict(name='02-tower-boundary', eye=[-70, 321, -31], target=[-114, 320, -81]),
    dict(name='03-tower-upper', eye=[-73, 525, -34], target=[-114, 512, -81]),
    dict(name='04-tower-crown', eye=[-75, 621, -35], target=[-114, 601, -81]),
]
for y in (-80, 320, 512, 1_000_000, -1_000_000):
    shots.append(dict(name=f'fixture-{y}', eye=[80, y + 7, 80], target=[66, y + 1, 66], fixtureY=y))
shots += [
    dict(name='10-tower-return', eye=[-75, 621, -35], target=[-114, 601, -81]),
    dict(name='11-tower-reload', eye=[-73, 525, -34], target=[-114, 512, -81], reload=True),
    dict(name='12-fixture-edit', eye=[80, 1_000_007, 80], target=[66, 1_000_001, 66], fixtureY=1_000_000, edit=True),
]
if args.fixture_only:
    shots = [shot for shot in shots if 'fixtureY' in shot and not shot.get('edit')]
    shots += [dict(name='fixture-nether', eye=[80, 519, 80], target=[66, 513, 66], fixtureY=512, dimension='minecraft:the_nether'),
              dict(name='fixture-dimension-return', eye=[80, 327, 80], target=[66, 321, 66], fixtureY=320, dimension='minecraft:overworld'),
              dict(name='fixture-return', eye=[80, 327, 80], target=[66, 321, 66], fixtureY=320),
              dict(name='fixture-reload', eye=[80, 1_000_007, 80], target=[66, 1_000_001, 66], fixtureY=1_000_000, reload=True),
              dict(name='12-fixture-edit', eye=[80, 1_000_007, 80], target=[66, 1_000_001, 66], fixtureY=1_000_000, edit=True)]
if args.vanilla and args.shaders:
    parser.error('The vanilla baseline cannot enable Oculus shaders.')
request = dict(createWorld=args.create_world, world=args.world, output=str(args.output.resolve()), expectShaders=args.shaders, expectEmbeddium=not args.vanilla, shots=shots)
if args.shots:
    requested = set(args.shots.split(','))
    selected = [shot for shot in shots if shot['name'] in requested]
    if {shot['name'] for shot in selected} != requested:
        parser.error('Unknown shot name')
    request['shots'] = selected
(game / 'endless-preview-request.json').write_text(json.dumps(request, indent=2), encoding='utf-8')
print(f'Request prepared: {len(request["shots"])} hidden-window framebuffer captures.')
print('Launch the isolated instance; inspect capture-manifest.json and failure.txt in the output folder.')
if args.launcher:
    args.output.mkdir(parents=True, exist_ok=True)
    for name in ('failure.txt', 'capture-manifest.json'):
        (args.output / name).unlink(missing_ok=True)
    startup = None
    if os.name == 'nt':
        startup = subprocess.STARTUPINFO()
        startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
        startup.wShowWindow = 0
    subprocess.Popen([str(args.launcher), '--launch', args.instance.name], startupinfo=startup)
    deadline = time.monotonic() + args.timeout
    expected = {shot['name'] + '.png' for shot in request['shots']}
    while time.monotonic() < deadline:
        failure = args.output / 'failure.txt'
        if failure.exists():
            raise SystemExit(failure.read_text())
        manifest = args.output / 'capture-manifest.json'
        if manifest.exists():
            try:
                records = json.loads(manifest.read_text())
            except json.JSONDecodeError:
                time.sleep(1)
                continue
            if {record['file'] for record in records} == expected:
                assert all(record['shadersActive'] == args.shaders for record in records)
                assert all(record['embeddium'] != args.vanilla for record in records)
                assert all(record['width'] == 1920 and record['height'] == 1080 for record in records)
                for record in records:
                    if record['embeddium'] and record.get('fixtureY') is not None and record['file'] != '12-fixture-edit.png':
                        checks = record.get('compatibilityRegressions', '')
                        assert 'native initial/dynamic sort, crack aliases/removal' in checks
                        assert all(check in checks for check in ('complete mesh output', 'unload/cancel/late upload'))
                        sky = record['dimension'] == 'minecraft:overworld'
                        assert ('dense roof edits' if sky else 'no-skylight dimension skip') in checks
                        if sky and record['fixtureY'] >= 320:
                            assert 'distant sky page/removal' in checks
                print(f'Validated {len(records)} native framebuffer captures: {manifest}')
                break
        time.sleep(1)
    else:
        raise SystemExit('Capture timeout; inspect progress.json and the client log.')
