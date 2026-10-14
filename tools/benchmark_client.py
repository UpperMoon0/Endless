#!/usr/bin/env python3
"""Fresh production Minecraft 1.21.1 clients; actual present intervals, no FPS counter sampling.

Reads public launcher component metadata, never account files. All runtime output
belongs in a new ignored build directory. Requires an interactive hardware display.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import time
import urllib.request
from benchmark_server import Rcon, digest, java_tool, percentile

ROOT = Path(__file__).resolve().parents[1]


def allowed(library):
    result = not library.get('rules')
    for rule in library.get('rules', []):
        if rule.get('os', {}).get('name', 'windows') == 'windows':
            result = rule['action'] == 'allow'
    return result


def library_path(name):
    group, artifact, version, *classifier = name.split(':')
    suffix = '-' + classifier[0] if classifier else ''
    return Path(group.replace('.', '/')) / artifact / version / f'{artifact}-{version}{suffix}.jar'


def resolve_library(library, cache, output):
    path = library_path(library['name'])
    info = library.get('downloads', {}).get('artifact', {})
    expected = info.get('sha1')
    local = cache / 'libraries' / path
    if local.exists() and (not expected or hashlib.sha1(local.read_bytes()).hexdigest() == expected):
        return local
    target = output / 'libraries' / path
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        url = info.get('url') or library.get('url', 'https://maven.fabricmc.net').rstrip('/') + '/' + path.as_posix()
        if expected is None:
            expected = urllib.request.urlopen(url + '.sha1', timeout=30).read().decode().split()[0]
        data = urllib.request.urlopen(url, timeout=60).read()
        if hashlib.sha1(data).hexdigest() != expected:
            raise RuntimeError('Library hash mismatch: ' + library['name'])
        target.write_bytes(data)
    return target


def client_classpath(cache, output, fabric):
    components = ['net.minecraft/1.21.1.json', 'org.lwjgl3/3.3.3.json']
    if fabric:
        components.append('net.fabricmc.fabric-loader/0.17.2.json')
    libraries = []
    for component in components:
        metadata = json.loads((cache / 'meta' / component).read_text())
        libraries.extend(library for library in metadata.get('libraries', []) if allowed(library))
        if metadata.get('mainJar'):
            libraries.append(metadata['mainJar'])
    if fabric:
        libraries.append({'name': 'net.fabricmc:intermediary:1.21.1'})
    return [resolve_library(library, cache, output) for library in libraries]


def compile_agent(java, output):
    classes = output / 'agent-classes'
    classes.mkdir()
    sources = [ROOT / 'tools/performance' / name for name in ('FrameAgent.java', 'FrameMetrics.java')]
    subprocess.run([java_tool(java, 'javac'), '--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED', '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([java_tool(java, 'jar'), 'cf', str(output / 'frame-helper.jar'), '-C', str(classes), 'endless/bench/FrameMetrics.class'], check=True)
    manifest = output / 'agent-manifest.txt'
    manifest.write_text('Premain-Class: endless.bench.FrameAgent\n\n')
    subprocess.run([java_tool(java, 'jar'), 'cfm', str(output / 'frame-agent.jar'), str(manifest), '-C', str(classes), 'endless/bench/FrameAgent.class', '-C', str(classes), 'endless/bench/FrameAgent$1.class', '-C', str(classes), 'endless/bench/FrameAgent$1$1.class', '-C', str(classes), 'endless/bench/FrameAgent$1$1$1.class'], check=True)


def summarize_frames(values):
    if not values or any(value <= 0 for value in values):
        raise ValueError('Positive frame intervals required')
    slowest = sorted(values, reverse=True)[:max(1, math.ceil(len(values) * .01))]
    return {'frames': len(values), 'seconds': sum(values) / 1e9,
            'average_fps': 1e9 / statistics.mean(values), 'one_percent_low_fps': 1e9 / statistics.mean(slowest),
            'median_ms': statistics.median(values) / 1e6, 'p95_ms': percentile(values, .95) / 1e6,
            'p99_ms': percentile(values, .99) / 1e6, 'max_ms': max(values) / 1e6}


def wait_log(process, path, marker, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'Process exited ({process.returncode}): {path}')
        if marker in path.read_text(errors='replace'):
            return
        time.sleep(.5)
    raise RuntimeError(f'Timeout waiting for {marker}: {path}')


def phase(directory, value):
    temporary = directory / 'phase.tmp'
    temporary.write_text(str(value))
    temporary.replace(directory / 'phase.txt')


def run(args, label, jar, repeat, sparse=False):
    directory = args.output / f'{label}-{"sparse" if sparse else "dense"}-{repeat}'
    directory.mkdir()
    server_dir, client_dir = directory / 'server', directory / 'client'
    y = 4096 if sparse else 80
    for game in (server_dir, client_dir):
        (game / 'mods').mkdir(parents=True)
        (game / 'config').mkdir()
        (game / 'config/endless.json').write_text(json.dumps({'buildHeight': {'minBuildHeight': -1048576, 'maxBuildHeight': 1048576}}))
        if label != 'vanilla':
            for dependency in args.dependency:
                shutil.copy2(dependency, game / 'mods' / dependency.name)
        if jar:
            shutil.copy2(jar, game / 'mods/endless.jar')
    (server_dir / 'eula.txt').write_text('eula=true\n')
    (server_dir / 'server.properties').write_text(f'online-mode=false\nserver-ip=127.0.0.1\nserver-port={args.port}\nenable-rcon=true\nrcon.port={args.port+1}\nrcon.password=endless-benchmark\nlevel-type=minecraft:flat\nlevel-seed=0\nview-distance=8\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=120000\n' + 'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    (client_dir / 'options.txt').write_text('version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:true\ntutorialStep:none\nrenderDistance:8\nsimulationDistance:5\nmaxFps:260\nenableVsync:false\ngraphicsMode:1\nentityDistanceScaling:1.0\nparticles:0\npauseOnLostFocus:false\nfullscreen:false\nfov:0.0\nguiScale:2\n')
    launcher = args.vanilla_server if label == 'vanilla' else args.fabric_server
    server_log, client_log = directory / 'server.log', directory / 'client.log'
    server = client = rcon = None
    with server_log.open('w') as sl, client_log.open('w') as cl:
        try:
            server = subprocess.Popen([java_tool(args.java, 'java'), '-Xms1G', '-Xmx2G', '-jar', str(launcher), 'nogui'], cwd=server_dir, stdout=sl, stderr=subprocess.STDOUT)
            wait_log(server, server_log, 'Done (')
            rcon = Rcon(args.port + 1)
            for command in ('gamerule doMobSpawning false', 'gamerule randomTickSpeed 0', 'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'weather clear', 'time set noon', 'forceload add -64 -64 127 127'):
                rcon.request(command)
            time.sleep(8)
            for x in range(0, 112, 16):
                for z in range(0, 112, 16):
                    response = rcon.request(f'fill {x} {y} {z} {x+7} {y+31} {z+7} minecraft:stone')
                    if '2048' not in response:
                        raise RuntimeError('Fixture fill failed: ' + response)
            cp = args.fabric_cp if label != 'vanilla' else args.vanilla_cp
            main = 'net.fabricmc.loader.impl.launch.knot.KnotClient' if label != 'vanilla' else 'net.minecraft.client.main.Main'
            command = [java_tool(args.java, 'java'), '-Xms1G', '-Xmx2G', '-Dfabric.debug.disableClassPathIsolation=true', '--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED', f'-javaagent:{args.output / "frame-agent.jar"}={directory}', '-cp', os.pathsep.join(map(str, cp)), main, '--username', 'FrameBench', '--uuid', '00000000-0000-0000-0000-000000000001', '--accessToken', 'offline', '--version', '1.21.1', '--gameDir', str(client_dir), '--assetsDir', str(args.cache / 'assets'), '--assetIndex', '17', '--width', '1280', '--height', '720', '--quickPlayMultiplayer', f'127.0.0.1:{args.port}']
            argfile = directory / 'client-args.txt'
            argfile.write_text('\n'.join('"' + word.replace('\\', '/').replace('"', '\\"') + '"' for word in command[1:]))
            client = subprocess.Popen([command[0], '@' + str(argfile)], cwd=client_dir, stdout=cl, stderr=subprocess.STDOUT)
            wait_log(server, server_log, 'FrameBench joined the game')
            rcon.request('gamemode spectator FrameBench')
            rcon.request(f'tp FrameBench 56 {y+36} -24 0 25')
            time.sleep(args.warmup)
            if 'ENDLESS_FRAME_HOOK' not in client_log.read_text(errors='replace'):
                raise RuntimeError('Present hook missing')
            for number, workload in enumerate(('static', 'orbit', 'edits'), 1):
                rcon.request(f'tp FrameBench 56 {y+36} -24 0 25')
                phase(directory, 1000 + number)
                time.sleep(2)
                phase(directory, 0)
                time.sleep(2)
                phase(directory, number)
                start = time.monotonic()
                step = 0
                while time.monotonic() - start < args.seconds:
                    if client.poll() is not None:
                        raise RuntimeError('Client exited during measurement')
                    if workload == 'orbit':
                        yaw = 40 * math.sin((time.monotonic() - start) * .5)
                        rcon.request(f'tp FrameBench 56 {y+36} -24 {yaw:.4f} 25')
                    elif workload == 'edits':
                        block = 'minecraft:stone' if step % 2 else 'minecraft:glass'
                        rcon.request(f'fill 48 {y+16} 0 55 {y+23} 7 {block}')
                    step += 1
                    time.sleep(.05)
                phase(directory, 0)
            phase(directory, -1)
            client.wait(timeout=60)
            rcon.request('stop')
            server.wait(timeout=90)
        finally:
            if rcon:
                rcon.close()
            for process in (client, server):
                if process and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=30)
    rows = list(csv.DictReader((directory / 'frames.csv').open()))
    result = {'label': label, 'sparse': sparse, 'repeat': repeat, 'jar_sha256': digest(jar) if jar else None,
              'metrics': {workload: summarize_frames([int(row['frame_ns']) for row in rows if int(row['phase']) == number]) for number, workload in enumerate(('static', 'orbit', 'edits'), 1)}}
    for metric in result['metrics'].values():
        if metric['frames'] < 200 or abs(metric['seconds'] - args.seconds) > 2:
            raise RuntimeError('Incomplete measured frame window')
    (directory / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'cache', 'vanilla-server', 'fabric-server', 'current', 'candidate', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--dependency', type=Path, action='append', default=[])
    parser.add_argument('--labels', nargs='+', choices=['vanilla', 'fabric', 'current', 'candidate'], default=['vanilla', 'fabric', 'current', 'candidate'])
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--port', type=int, default=25785)
    parser.add_argument('--dense-only', action='store_true')
    args = parser.parse_args()
    for name in ('java', 'cache', 'vanilla_server', 'fabric_server', 'current', 'candidate', 'output'):
        setattr(args, name, getattr(args, name).resolve())
    args.dependency = [p.resolve() for p in args.dependency]
    args.output.mkdir(parents=True, exist_ok=False)
    compile_agent(args.java, args.output)
    args.vanilla_cp = client_classpath(args.cache, args.output, False)
    args.fabric_cp = client_classpath(args.cache, args.output, True)
    results = []
    for repeat in range(args.repeats):
        variants = [(label, getattr(args, label) if label in ('current', 'candidate') else None, sparse) for label in args.labels for sparse in ((False, True) if label in ('current', 'candidate') and not args.dense_only else (False,))]
        for label, jar, sparse in variants[::(-1 if repeat % 2 else 1)]:
            results.append(run(args, label, jar, repeat, sparse))
            (args.output / 'results.json').write_text(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
