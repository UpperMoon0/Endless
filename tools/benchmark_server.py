#!/usr/bin/env python3
"""Serial fresh-JVM Minecraft 1.21.1 vanilla/Fabric/Endless benchmark.

Requires Java 21, an official server jar, a Fabric server launcher, and identical
Fabric API / Architectury dependency jars for both Endless candidates. Runtime
files stay under build/. No player, renderer, FPS or multiplayer claims.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import math
import os
import platform
import shutil
import socket
import statistics
import struct
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def java_tool(java, name):
    return str(java / "bin" / (name + (".exe" if os.name == "nt" else "")))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class Rcon:
    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.request("endless-benchmark", 3)

    def read(self, n):
        result = b""
        while len(result) < n:
            data = self.socket.recv(n - len(result))
            if not data:
                raise RuntimeError("RCON disconnected")
            result += data
        return result

    def request(self, command, kind=2):
        body = struct.pack("<ii", 7, kind) + command.encode() + b"\0\0"
        self.socket.sendall(struct.pack("<i", len(body)) + body)
        size = struct.unpack("<i", self.read(4))[0]
        packet = self.read(size)
        identity, response_kind = struct.unpack("<ii", packet[:8])
        if identity == -1:
            raise RuntimeError("RCON authentication failed")
        return packet[8:-2].decode(errors="replace")

    def close(self):
        self.socket.close()


def datapack(world, sparse):
    pack = world / "datapacks/benchmark"
    functions = pack / "data/bench/function"
    functions.mkdir(parents=True)
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 48, "description": "Endless deterministic performance fixture"}}))
    y = 4096 if sparse else 80
    # 64 forced chunks, 256 X/Z columns queried across them. Height work is
    # native /execute positioned over; no mocked ChunkAccess or palette loops.
    queries = [f"execute positioned {x} 0 {z} positioned over motion_blocking run scoreboard players add queries bench 1"
               for x in range(0, 128, 8) for z in range(0, 128, 8)]
    (functions / "heights.mcfunction").write_text("\n".join(queries) + "\n")
    edits = ["scoreboard players add phase bench 1", "scoreboard players operation phase bench %= two bench",
             f"execute if score phase bench matches 0 run fill 0 {y} 0 31 {y+1} 31 minecraft:stone",
             f"execute if score phase bench matches 1 run fill 0 {y} 0 31 {y+1} 31 minecraft:air",
             "function bench:heights"]
    (functions / "edits.mcfunction").write_text("\n".join(edits) + "\n")
    tags = pack / "data/minecraft/tags/function"
    tags.mkdir(parents=True)
    (tags / "tick.json").write_text(json.dumps({"values": ["bench:tick"]}))
    (functions / "tick.mcfunction").write_text("execute if score enabled bench matches 1 run function bench:edits\n"
        "execute if score enabled bench matches 2 run function bench:heights\n")


def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def summarize(rows):
    return {"ticks": len(rows), **{key: {"median": statistics.median([float(r[key]) for r in rows]),
                "mean": statistics.mean([float(r[key]) for r in rows]),
                "p95": percentile([float(r[key]) for r in rows], .95),
                "p99": percentile([float(r[key]) for r in rows], .99)}
            for key in ("tick_ns", "cpu_ns", "allocated_bytes")}}


def select_sprint(rows, sprint_id, expected):
    measured = [row for row in rows if int(row["sprint_id"]) == sprint_id]
    if len(measured) != expected:
        raise RuntimeError(f"Sprint {sprint_id} had {len(measured)} ticks, expected {expected}")
    return measured


def run(args, label, jar, repeat, sparse=False):
    name = f"{label}-{'sparse' if sparse else 'dense'}-{repeat}"
    directory = args.output / name
    # Never reset or overwrite an existing world; each invocation owns new paths.
    directory.mkdir(parents=True, exist_ok=False)
    datapack(directory / "world", sparse)
    mods = directory / "mods"
    mods.mkdir()
    if jar:
        shutil.copy2(jar, mods / "endless.jar")
    if label != "vanilla":
        for dep in args.dependency:
            shutil.copy2(dep, mods / Path(dep).name)
    (directory / "config").mkdir()
    (directory / "config/endless.json").write_text(json.dumps({"buildHeight": {"minBuildHeight": -1048576, "maxBuildHeight": 1048576}}))
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(
        f"online-mode=false\nserver-ip=127.0.0.1\nserver-port={args.port}\n"
        f"enable-rcon=true\nrcon.port={args.port+1}\nrcon.password=endless-benchmark\n"
        "level-type=minecraft:flat\nlevel-seed=0\nview-distance=4\nsimulation-distance=4\n"
        "spawn-protection=0\nmax-tick-time=120000\nsync-chunk-writes=true\n"
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n')
    ticks_file = directory / "ticks.csv"
    launcher = args.vanilla if label == "vanilla" else args.fabric
    command = [java_tool(args.java, "java"), "-Xms1G", "-Xmx2G",
        "-Dfabric.debug.disableClassPathIsolation=true",
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        f"-Dendless.bench.helper={args.helper}",
        f"-javaagent:{args.agent}={ticks_file}", "-jar", str(launcher), "nogui"]
    windows = []
    rcon = None
    with (directory / "server.log").open("w") as log:
        process = subprocess.Popen(command, cwd=directory, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited {process.returncode}; see server.log")
                content = (directory / "server.log").read_text(errors="replace")
                if "Done (" in content:
                    rcon = Rcon(args.port + 1)
                    break
                time.sleep(.5)
            if rcon is None:
                raise RuntimeError(f"{name}: readiness timeout")
            if any(marker not in content for marker in ("ENDLESS_BENCH_TICK_HOOK_PASS", "ENDLESS_BENCH_SPRINT_HOOK_PASS")):
                raise RuntimeError("Missing tick or sprint measurement hook")
            commands = ["gamerule doMobSpawning false", "gamerule randomTickSpeed 0", "gamerule doDaylightCycle false",
                        "gamerule doWeatherCycle false", "weather clear", "time set noon", "scoreboard objectives add bench dummy",
                        "scoreboard players set two bench 2", "scoreboard players set phase bench 0",
                        "scoreboard players set enabled bench 0", "forceload add 0 0 127 127"]
            for command in commands:
                rcon.request(command)
            # Settle chunk generation and lighting before running unlimited ticks.
            time.sleep(8)
            y = 4096 if sparse else 80
            fill_response = rcon.request(f"fill 0 {y} 0 31 {y+1} 31 minecraft:stone")
            if "2048" not in fill_response:
                raise RuntimeError(f"Initial native fill rejected: {fill_response}")
            time.sleep(2)
            height_response = rcon.request("function bench:heights")
            if "Running function" not in height_response:
                raise RuntimeError(f"Native height workload rejected: {height_response}")

            def sprint(amount):
                start = time.time_ns() // 1_000_000
                response = rcon.request(f"tick sprint {amount}")
                if "Sprint" not in response and "sprint" not in response:
                    raise RuntimeError(f"tick sprint rejected: {response}")
                limit = time.monotonic() + 180
                while time.monotonic() < limit:
                    text = rcon.request("tick query")
                    # Sprint completion returns to the normal 20 TPS target.
                    if "sprinting" not in text.lower():
                        return start, time.time_ns() // 1_000_000
                    time.sleep(.05)
                raise RuntimeError("Sprint timeout")

            for workload, enabled in (("idle", 0), ("reads", 2), ("edits", 1)):
                rcon.request(f"scoreboard players set enabled bench {enabled}")
                sprint(args.warmup)
                start, end = sprint(args.ticks)
                windows.append({"workload": workload, "start_ms": start, "end_ms": end})
            rcon.request("scoreboard players set enabled bench 0")
            query_response = rcon.request("scoreboard players get queries bench")
            import re
            match = re.search(r"has (\d+)", query_response)
            if not match or int(match[1]) < 256 * (args.warmup + args.ticks):
                raise RuntimeError(f"Height workload did not execute: {query_response}")
            # Confirm writes used the real requested Y before the JVM shuts down.
            y = 4096 if sparse else 80
            rcon.request(f"setblock 0 {y} 0 minecraft:stone")
            proof = rcon.request(f"execute if block 0 {y} 0 minecraft:stone run scoreboard players set proof bench 1")
            if "to 1" not in proof:
                raise RuntimeError(f"Native block write failed at Y={y}: {proof}")
            rcon.request("stop")
            process.wait(timeout=90)
        finally:
            if rcon:
                rcon.close()
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=30)
    if process.returncode != 0:
        raise RuntimeError(f"{name}: nonzero exit {process.returncode}")
    if sparse and not list((directory / "world/endless/vertical").glob("c.*/p.*.nbt")):
        raise RuntimeError("High-Y workload did not persist sparse storage")
    rows = list(csv.DictReader(ticks_file.open()))
    for sprint_id in (1, 3, 5):
        select_sprint(rows, sprint_id, args.warmup)
    results = {}
    for window in windows:
        sprint_id = {"idle": 2, "reads": 4, "edits": 6}[window["workload"]]
        measured = select_sprint(rows, sprint_id, args.ticks)
        results[window["workload"]] = summarize(measured)
    result = {"label": label, "sparse": sparse, "repeat": repeat, "jar_sha256": digest(jar) if jar else None,
              "height_queries": int(match[1]),
              "windows": windows, "metrics": results}
    (directory / "result.json").write_text(json.dumps(result, indent=2))
    print(name, json.dumps(results), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("java", "vanilla", "fabric", "current", "candidate", "output"):
        parser.add_argument(f"--{key}", type=Path, required=True)
    parser.add_argument("--dependency", type=Path, action="append", default=[])
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--ticks", type=int, default=1200)
    parser.add_argument("--warmup", type=int, default=600)
    parser.add_argument("--port", type=int, default=25685)
    parser.add_argument("--labels", nargs="+", default=["vanilla", "fabric", "current", "candidate"])
    args = parser.parse_args()
    for key in ("java", "vanilla", "fabric", "current", "candidate", "output"):
        setattr(args, key, getattr(args, key).resolve())
    args.output.mkdir(parents=True, exist_ok=True)
    metadata = {"minecraft": "1.21.1", "fabric_loader": "0.17.2", "platform": platform.platform(),
        "processor": os.environ.get("PROCESSOR_IDENTIFIER", platform.processor()), "logical_cpus": os.cpu_count(),
        "java": subprocess.check_output([java_tool(args.java, "java"), "-version"], stderr=subprocess.STDOUT, text=True),
        "source_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "source_diff_sha256": hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD"], cwd=ROOT)).hexdigest(),
        "input_sha256": {key: digest(getattr(args, key)) for key in ("vanilla", "fabric", "current", "candidate")},
        "dependencies": {p.name: digest(p) for p in args.dependency}, "arguments": {k: str(v) for k, v in vars(args).items()},
        "scope": "Dedicated server only; serial fresh JVMs; exact sprint ticks; CPU and allocations are server-thread only; no FPS/GC/peak RAM claims. Windows CPU counters have coarse granularity; use means, not per-tick CPU percentiles."}
    (args.output / "metadata.json").write_text(json.dumps(metadata, indent=2))
    classes = args.output / "agent-classes"
    classes.mkdir(exist_ok=True)
    source = ROOT / "tools/performance/TickAgent.java"
    subprocess.run([java_tool(args.java, "javac"), "--add-exports", "java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                    "-d", str(classes), str(source), str(source.with_name("TickMetrics.java"))], check=True)
    manifest = args.output / "agent-manifest.txt"
    manifest.write_text("Premain-Class: endless.bench.TickAgent\n\n")
    args.agent = args.output / "tick-agent.jar"
    subprocess.run([java_tool(args.java, "jar"), "cfm", str(args.agent), str(manifest), "-C", str(classes), "."], check=True)
    args.helper = args.output / "tick-helper.jar"
    subprocess.run([java_tool(args.java, "jar"), "cf", str(args.helper), "-C", str(classes), "endless/bench/TickMetrics.class"], check=True)
    results = []
    for repeat in range(args.repeats):
        # Reverse order on alternate rounds to reduce thermal/order bias.
        labels = args.labels if repeat % 2 == 0 else list(reversed(args.labels))
        for label in labels:
            jar = getattr(args, label) if label in ("current", "candidate") else None
            results.append(run(args, label, jar, repeat))
            if jar:
                results.append(run(args, label, jar, repeat, sparse=True))
        (args.output / "results.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
