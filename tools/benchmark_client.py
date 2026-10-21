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
import re
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import time
import threading
import platform
import urllib.request
from benchmark_server import Rcon, digest, java_tool, percentile

ROOT = Path(__file__).resolve().parents[1]
WORKLOADS = ('static', 'orbit', 'edits')
PHASE_RETRIES = {}


def planned_runs(labels, repeats, dense_only=False, sparse_only=False):
    """Freeze the exact requested matrix before the first JVM is launched."""
    return [{'label': label, 'sparse': sparse, 'repeat': repeat}
            for repeat in range(repeats)
            for label in labels
            for sparse in ((True,) if sparse_only else
                           ((False, True) if label in ('current', 'candidate') and not dense_only else (False,)))]


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
    # Windows readers may briefly deny FILE_SHARE_DELETE. Preserve atomic
    # publication and fail boundedly rather than truncating the live control file.
    for attempt in range(100):
        try:
            temporary.replace(directory / 'phase.txt')
            PHASE_RETRIES[str(directory)] = PHASE_RETRIES.get(str(directory), 0) + attempt
            return
        except PermissionError:
            if attempt == 99:
                raise
            time.sleep(.01)


def wait_phase(directory, value, client, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if client.poll() is not None:
            raise RuntimeError('Client exited before native frame acknowledgement')
        try:
            if (directory / 'phase-ack.txt').read_text().strip() == str(value):
                return
        except OSError:
            pass
        time.sleep(.05)
    raise RuntimeError('Native present phase acknowledgement timed out')



def wait_tall_ready(directory, client, rendering, height, timeout):
    """Require the endpoint and both renderer queues to settle before any FPS window."""
    deadline = time.monotonic() + timeout
    stable = 0
    last_signature = None
    while time.monotonic() < deadline:
        phase(directory, 2000)
        wait_phase(directory, 2000, client)
        receipt = dict(line.split('=',1) for line in (directory/'scene-2000.txt').read_text().splitlines())
        native = directory/'renderer-2000.txt'
        queues = re.findall(r'(?:P[012]|U)=(\d+)', native.read_text()) if native.exists() else []
        if native.exists() and len(queues)!=4:
            raise RuntimeError('Native queue observer format changed; refusing an unsettled comparison')
        lod = directory/'lod-2000.txt'
        pending = re.search(r'\bpendingVisible=(\d+)(?=[,}]|$)',lod.read_text()) if lod.exists() else None
        if rendering['verticalLodSections'] > rendering['verticalRenderSections'] and pending is None:
            raise RuntimeError('LOD queue observer missing or malformed; refusing an unsettled comparison')
        endpoint = max(rendering.values())*8 < height-36 or int(receipt.get('yellowPixels','0')) >= 60
        settled = endpoint and all(int(value)==0 for value in queues) and (pending is None or int(pending[1])==0)
        signature = (receipt.get('neutralPixels'),receipt.get('yellowPixels'),
                     native.read_text() if native.exists() else '',lod.read_text() if lod.exists() else '')
        stable = stable+1 if settled and signature==last_signature else 0
        last_signature = signature
        if stable >= 3:
            return
        phase(directory, 0)
        wait_phase(directory, 0, client)
        time.sleep(1)
    raise RuntimeError('Tall scene did not settle with a visible endpoint; no performance result accepted')


class ProcessResources:
    """External sampling; no instrumentation or file I/O in the game's render thread."""
    def __init__(self, directory, client, server):
        import psutil
        self.directory, self.rows, self.stop = directory, [], threading.Event()
        self.processes = {'client': psutil.Process(client.pid), 'server': psutil.Process(server.pid)}
        self.thread = threading.Thread(target=self.sample, daemon=True)
        self.thread.start()

    def sample(self):
        while not self.stop.is_set():
            try:
                phase_value = int((self.directory / 'phase.txt').read_text())
                stamp = time.monotonic()
                import psutil
                self.rows.append({'phase':phase_value,'time':stamp,'role':'host',
                    'cpu_percent':psutil.cpu_percent(None),'available_bytes':psutil.virtual_memory().available})
                for role, process in self.processes.items():
                    cpu = process.cpu_times()
                    self.rows.append({'phase': phase_value, 'role': role, 'time': stamp,
                                      'rss': process.memory_info().rss, 'cpu': cpu.user + cpu.system})
            except Exception:
                pass
            self.stop.wait(.25)

    def finish(self):
        self.stop.set(); self.thread.join(timeout=2)
        (self.directory / 'resources.json').write_text(json.dumps(self.rows, indent=2))
        result = {}
        for phase_number, workload in enumerate(WORKLOADS, 1):
            host=[row for row in self.rows if row['phase']==phase_number and row['role']=='host']
            result[workload] = {'host':{'mean_cpu_percent':statistics.mean(row['cpu_percent'] for row in host),
                'minimum_available_bytes':min(row['available_bytes'] for row in host)}}
            for role in self.processes:
                rows = [row for row in self.rows if row['phase'] == phase_number and row['role'] == role]
                if len(rows) < 8:
                    raise RuntimeError('Incomplete resource sample window')
                result[workload][role] = {'peak_rss_bytes': max(row['rss'] for row in rows),
                    'mean_rss_bytes': statistics.mean(row['rss'] for row in rows),
                    'cpu_cores': (rows[-1]['cpu']-rows[0]['cpu'])/(rows[-1]['time']-rows[0]['time'])}
        return result


def run(args, label, jar, repeat, sparse=False):
    directory = args.output / f'{label}-{"sparse" if sparse else "dense"}-{repeat}'
    directory.mkdir()
    frozen_jar_hash = digest(jar) if jar else None
    server_dir, client_dir = directory / 'server', directory / 'client'
    y = 4096 if sparse else 80
    tall = getattr(args, 'tower_height', 0)
    native = getattr(args, label + '_sections', 32)
    lod = getattr(args, 'candidate_lod_sections', 0) if label == 'candidate' else 0
    rendering = {'verticalRenderSections': native, 'verticalLodSections': lod}
    edit_y = y + (512 if tall else 16)
    edit_top = edit_y + (31 if tall else 7)
    edit_count = '2048' if tall else '512'
    camera_z, pitch = (-96, -55) if tall else (-24, 25)
    for game in (server_dir, client_dir):
        (game / 'mods').mkdir(parents=True)
        (game / 'config').mkdir()
        (game / 'config/endless.json').write_text(json.dumps({'buildHeight': {'minBuildHeight': -1048576, 'maxBuildHeight': 1048576}, 'rendering': rendering}))
        if label != 'vanilla':
            for dependency in args.dependency:
                shutil.copy2(dependency, game / 'mods' / dependency.name)
        if jar:
            shutil.copy2(jar, game / 'mods/endless.jar')
    if args.shader_pack:
        packs=client_dir/'shaderpacks'; packs.mkdir()
        shutil.copy2(args.shader_pack,packs/args.shader_pack.name)
        (client_dir/'config/iris.properties').write_text('enableShaders=true\nshaderPack='+args.shader_pack.name+'\n')
        if args.shader_options:
            shutil.copy2(args.shader_options,packs/(args.shader_pack.name+'.txt'))
    (server_dir / 'eula.txt').write_text('eula=true\n')
    (server_dir / 'server.properties').write_text(f'online-mode=false\nserver-ip=127.0.0.1\nserver-port={args.port}\nenable-rcon=true\nrcon.port={args.port+1}\nrcon.password=endless-benchmark\nlevel-type=minecraft:flat\nlevel-seed=0\nview-distance=8\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=120000\n' + 'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    (client_dir / 'options.txt').write_text('version:3955\nonboardAccessibility:false\nskipMultiplayerWarning:true\ntutorialStep:none\nrenderDistance:8\nsimulationDistance:5\nmaxFps:260\nenableVsync:false\ngraphicsMode:1\nentityDistanceScaling:1.0\nparticles:0\npauseOnLostFocus:false\nfullscreen:false\nfov:0.0\nguiScale:2\n')
    launcher = args.vanilla_server if label == 'vanilla' else args.fabric_server
    server_log, client_log = directory / 'server.log', directory / 'client.log'
    server = client = rcon = None
    command_counts = {}
    resource_monitor = None
    resources = {}
    readiness = {}
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
                    for dy in range(0, tall or 32, 32):
                        material = 'yellow_concrete' if tall and dy >= tall-256 else 'stone'
                        response = rcon.request(f'fill {x} {y+dy} {z} {x+7} {y+dy+31} {z+7} minecraft:{material}')
                        if '2048' not in response:
                            raise RuntimeError('Fixture fill failed: ' + response)
            cp = args.fabric_cp if label != 'vanilla' else args.vanilla_cp
            main = 'net.fabricmc.loader.impl.launch.knot.KnotClient' if label != 'vanilla' else 'net.minecraft.client.main.Main'
            command = [java_tool(args.java, 'java'), '-Xms1G', '-Xmx2G', '-Dfabric.debug.disableClassPathIsolation=true', '-Dendless.lodMetrics=true', f'-Dendless.bench.noFog={str(args.disable_fog).lower()}', f'-Dendless.bench.farPlane={args.far_plane}', '--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED', f'-javaagent:{args.output / "frame-agent.jar"}={directory}', '-cp', os.pathsep.join(map(str, cp)), main, '--username', 'FrameBench', '--uuid', '00000000-0000-0000-0000-000000000001', '--accessToken', 'offline', '--version', '1.21.1', '--gameDir', str(client_dir), '--assetsDir', str(args.cache / 'assets'), '--assetIndex', '17', '--width', str(args.width), '--height', str(args.height), '--quickPlayMultiplayer', f'127.0.0.1:{args.port}']
            argfile = directory / 'client-args.txt'
            argfile.write_text('\n'.join('"' + word.replace('\\', '/').replace('"', '\\"') + '"' for word in command[1:]))
            client = subprocess.Popen([command[0], '@' + str(argfile)], cwd=client_dir, stdout=cl, stderr=subprocess.STDOUT)
            wait_log(server, server_log, 'FrameBench joined the game')
            rcon.request('gamemode spectator FrameBench')
            rcon.request(f'tp FrameBench 56 {y+36} {camera_z} 0 {pitch}')
            wait_log(client, client_log, 'Loaded 2 advancements')
            joined = time.monotonic()
            phase(directory, 0)
            resource_monitor = ProcessResources(directory, client, server)
            time.sleep(args.warmup)
            if tall:
                wait_tall_ready(directory, client, rendering, tall, args.ready_timeout)
            readiness = {'join_to_ready_seconds': time.monotonic()-joined,
                'peak_rss_bytes': {role: max((row['rss'] for row in resource_monitor.rows if row['role']==role), default=0)
                                   for role in ('client','server')}}
            if args.far_plane and 'ENDLESS_BENCH_FAR' not in client_log.read_text(errors='replace'):
                raise RuntimeError('Matched far projection hook missing')
            if args.disable_fog and client_log.read_text(errors='replace').count('ENDLESS_BENCH_FOG ') != 2:
                raise RuntimeError('Both native fog hooks must be verified for matched tall-view visibility')
            if args.shader_pack and 'Using shaderpack: '+args.shader_pack.name not in client_log.read_text(errors='replace'):
                raise RuntimeError('Requested shader pack was not proven active')
            if 'ENDLESS_FRAME_HOOK' not in client_log.read_text(errors='replace'):
                raise RuntimeError('Present hook missing')
            for number, workload in enumerate(WORKLOADS, 1):
                rcon.request(f'tp FrameBench 56 {y+36} {camera_z} 0 {pitch}')
                time.sleep(2)
                phase(directory, 1000 + number)
                wait_phase(directory, 1000 + number, client)
                time.sleep(2)
                phase(directory, 0)
                time.sleep(2)
                phase(directory, number)
                wait_phase(directory, number, client)
                start = time.monotonic()
                cadence = .2 if tall and workload=='edits' else .05
                steps = math.ceil(args.seconds/cadence)
                for step in range(steps):
                    delay=start+step*cadence-time.monotonic()
                    if delay>0: time.sleep(delay)
                    if client.poll() is not None:
                        raise RuntimeError('Client exited during measurement')
                    if workload == 'orbit':
                        yaw = 40 * math.sin(step*cadence*.5)
                        rcon.request(f'tp FrameBench 56 {y+36} {camera_z} {yaw:.4f} {pitch}')
                    elif workload == 'edits':
                        block = 'minecraft:stone' if step % 2 else ('minecraft:copper_block' if tall else 'minecraft:glass')
                        response = rcon.request(f'fill 48 {edit_y} 0 55 {edit_top} 7 {block}')
                        if edit_count not in response:
                            raise RuntimeError('Native edit failed: ' + response)
                delay=start+args.seconds-time.monotonic()
                if delay>0: time.sleep(delay)
                phase(directory, 0)
                wait_phase(directory, 0, client)
                command_counts[workload] = steps if workload != 'static' else 0
            response = rcon.request(f'fill 48 {edit_y} 0 55 {edit_top} 7 minecraft:redstone_block')
            if edit_count not in response:
                raise RuntimeError('Native visible edit fixture failed: ' + response)
            time.sleep(3)
            phase(directory, 1004)
            wait_phase(directory, 1004, client)
            if tall:
                # Native opaque foreground must hide far LOD after the full world pass.
                for bottom in range(64,256,64):
                    response=rcon.request(f'fill -32 {y+bottom} -91 144 {y+bottom+63} -91 minecraft:magenta_concrete')
                    if '11328' not in response: raise RuntimeError('Occluder fill failed: '+response)
                deadline=time.monotonic()+60
                while True:
                    time.sleep(1)
                    phase(directory,1005);wait_phase(directory,1005,client)
                    receipt=dict(line.split('=',1) for line in (directory/'scene-1005.txt').read_text().splitlines())
                    prior=dict(line.split('=',1) for line in (directory/'scene-1004.txt').read_text().splitlines())
                    if int(receipt.get('magentaPixels','0'))>=5000 and int(receipt.get('yellowPixels','0'))<=max(8,int(prior.get('yellowPixels','0'))*.03): break
                    if time.monotonic()>deadline: raise RuntimeError('Opaque native foreground did not mask distant geometry')
                    phase(directory,0);wait_phase(directory,0,client)
            resources = resource_monitor.finish()
            resource_monitor = None
            phase(directory, -1)
            client.wait(timeout=60)
            rcon.request('stop')
            server.wait(timeout=90)
        finally:
            if resource_monitor:
                resource_monitor.stop.set(); resource_monitor.thread.join(timeout=2)
            if rcon:
                rcon.close()
            for process in (client, server):
                if process and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=30)
    rows = list(csv.DictReader((directory / 'frames.csv').open()))
    for number in range(1001, 1005):
        receipt = dict(line.split('=', 1) for line in (directory / f'scene-{number}.txt').read_text().splitlines())
        if (int(receipt['width']), int(receipt['height'])) != (args.width, args.height) or (not args.shader_pack and int(receipt['neutralPixels']) < (5_000 if tall else 100_000)):
            raise RuntimeError('Expected visible stone fixture and framebuffer dimensions missing')
        if tall and max(native,lod)*8>=tall-36 and int(receipt.get('yellowPixels','0'))<8:
            raise RuntimeError('Distant tower-top marker missing; shortened/incomplete views cannot pass')
        if number==1001 and tall and label=='candidate' and lod>native and not args.shader_pack and not args.disable_fog:
            rgb=int(receipt['seamRgb']);channels=[(rgb>>shift)&255 for shift in (16,8,0)]
            if max(channels)>150 or max(channels)-min(channels)>12:
                raise RuntimeError('Native/LOD transition is fogged or missing in the framebuffer')
        expected_edit = not tall or max(native,lod)*8 >= edit_top-y-36
        if number == 1004 and expected_edit and int(receipt['redPixels']) < (8 if tall else 500):
            raise RuntimeError('Native block edit did not appear in the framebuffer')
    if jar and (digest(jar)!=frozen_jar_hash or digest(client_dir/'mods/endless.jar')!=frozen_jar_hash):
        raise RuntimeError('Candidate input changed during the benchmark')
    gpu = (directory / 'gpu.txt').read_text()
    result = {'label': label, 'sparse': sparse, 'repeat': repeat, 'jar_sha256': frozen_jar_hash,
              'gpu': gpu, 'rendering': rendering, 'tower_height': tall, 'full_tower_expected': max(native,lod)*8>=tall-36,
              'native_command_counts': command_counts, 'control_file_retries': PHASE_RETRIES.get(str(directory),0), 'resources': resources, 'readiness': readiness,
              'metrics': {workload: summarize_frames([int(row['frame_ns']) for row in rows if int(row['phase']) == number]) for number, workload in enumerate(WORKLOADS, 1)}}
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
    parser.add_argument('--shader-pack', type=Path)
    parser.add_argument('--shader-options', type=Path)
    parser.add_argument('--width', type=int, default=1280)
    parser.add_argument('--height', type=int, default=720)
    parser.add_argument('--ready-timeout', type=int, default=600)
    parser.add_argument('--far-plane', type=float, default=0, help='Benchmark-only minimum projection far plane for equal visible geometry')
    parser.add_argument('--disable-fog', action='store_true', help='Benchmark-only equal native fog distances, verified by agent receipts')
    parser.add_argument('--tower-height', type=int, default=0)
    parser.add_argument('--current-sections', type=int, default=32)
    parser.add_argument('--candidate-sections', type=int, default=32)
    parser.add_argument('--candidate-lod-sections', type=int, default=0)
    parser.add_argument('--dense-only', action='store_true')
    parser.add_argument('--sparse-only', action='store_true')
    args = parser.parse_args()
    if min(args.width,args.height)<360 or max(args.width,args.height)>3840:
        parser.error('Framebuffer dimensions must be in [360,3840]')
    if args.repeats < 1 or args.seconds < 10 or args.warmup < 5 or len(set(args.labels)) != len(args.labels):
        parser.error('Use positive repeats, at least 10 measured seconds, at least 5 warmup seconds, and unique labels')
    if args.tower_height and (args.tower_height % 32 or args.tower_height < 544 or args.tower_height > 4096):
        parser.error('Tower height must be a multiple of 32 in [544, 4096]')
    if args.sparse_only and (args.dense_only or any(label in ('vanilla', 'fabric') for label in args.labels)):
        parser.error('Sparse-only requires Endless variants and cannot be combined with dense-only')
    for name in ('java', 'cache', 'vanilla_server', 'fabric_server', 'current', 'candidate', 'output'):
        setattr(args, name, getattr(args, name).resolve())
    args.dependency = [p.resolve() for p in args.dependency]
    if args.shader_pack: args.shader_pack=args.shader_pack.resolve(strict=True)
    if args.shader_options: args.shader_options=args.shader_options.resolve(strict=True)
    args.output.mkdir(parents=True, exist_ok=False)
    compile_agent(args.java, args.output)
    args.vanilla_cp = client_classpath(args.cache, args.output, False)
    args.fabric_cp = client_classpath(args.cache, args.output, True)
    inputs = [ROOT/'tools/benchmark_client.py', ROOT/'tools/benchmark_server.py', ROOT/'tools/performance/FrameAgent.java', ROOT/'tools/performance/FrameMetrics.java', args.vanilla_server, args.fabric_server, args.current, args.candidate, *args.dependency,
              *args.vanilla_cp, *args.fabric_cp, args.output / 'frame-agent.jar', args.output / 'frame-helper.jar']
    if args.shader_pack: inputs.append(args.shader_pack)
    if args.shader_options: inputs.append(args.shader_options)
    plan = planned_runs(args.labels, args.repeats, args.dense_only, args.sparse_only)
    import psutil
    (args.output / 'metadata.json').write_text(json.dumps({'host':{'os':platform.platform(),'cpu_logical':psutil.cpu_count(),
        'physical_memory_bytes':psutil.virtual_memory().total},'dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()), 'source': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'planned_runs': plan,
        'width':args.width,'height':args.height,'shader_pack': args.shader_pack.name if args.shader_pack else None, 'ready_timeout': args.ready_timeout, 'far_plane': args.far_plane, 'disable_fog': args.disable_fog, 'tower_height': args.tower_height, 'current_sections': args.current_sections, 'candidate_sections': args.candidate_sections, 'candidate_lod_sections': args.candidate_lod_sections,
        'warmup_seconds': args.warmup, 'measured_seconds': args.seconds, 'repeats': args.repeats,
        'inputs': {str(path): digest(path) for path in sorted(set(inputs))}}, indent=2))
    results = []
    for repeat in range(args.repeats):
        variants = [entry for entry in plan if entry['repeat'] == repeat]
        for entry in variants[::(-1 if repeat % 2 else 1)]:
            label = entry['label']
            jar = getattr(args, label) if label in ('current', 'candidate') else None
            results.append(run(args, label, jar, repeat, entry['sparse']))
            (args.output / 'results.json').write_text(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
