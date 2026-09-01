#!/usr/bin/env python3
"""Launch real dedicated servers and auto-joining clients for Endless.

Three scenarios close the server-authority model in both directions:

A. extended-server — the server is configured with an extended build range
   ([-1024, 1024)) and the client with the vanilla range ([-64, 320)). The
   client must adopt the server's authoritative range.
B. baseline-endless-vanilla-server — an Endless server whose world range is
   vanilla with a client whose local config is extended. The server
   deliberately sends no login query for a vanilla range, so the client must
   enter the world on the vanilla baseline instead of its extended config.
C. baseline-no-endless — a genuine vanilla server (official Mojang jar, no
   Endless) with a client whose local config is extended. Neither loader's
   Endless login exchange runs, so the client must still enter the world on
   the vanilla baseline.

In every scenario a successful join prints ENDLESS_LIVE_JOIN_TEST_PASS; a
failed one prints ENDLESS_LIVE_JOIN_TEST_FAIL.

Used by .github/workflows/live-join-test.yml to detect regressions in the
login-phase height sync on both loaders before the PR merges.
A compile-passing build that fails this test would still be rejected.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path


PASS_MARKER = "ENDLESS_LIVE_JOIN_TEST_PASS"
FAIL_MARKER = "ENDLESS_LIVE_JOIN_TEST_FAIL"
# Printed at ClientboundLoginPacket handling, before the client world exists.
# A pre-login failure also prints FAIL_MARKER, but matching it here fails the
# test immediately instead of waiting for the post-join timeout.
PRE_LOGIN_FAIL_MARKER = "ENDLESS_PRE_LOGIN_RANGE_FAIL"
SERVER_READY_MARKERS = ("Done (", "For help, type \"help\"")
DEFAULT_TIMEOUT = 360

# Shared config presets. The client's on-disk config deliberately disagrees
# with the expectation so that a leaked local config is caught.
EXTENDED_BUILD_HEIGHT = {"minBuildHeight": -1024, "maxBuildHeight": 1024}
VANILLA_BUILD_HEIGHT = {"minBuildHeight": -64, "maxBuildHeight": 320}

MC_VERSION = "1.20.1"

TARGETS = {
    "fabric-1.20.1": "fabric",
    "forge-1.20.1": "forge",
}


@dataclass(frozen=True)
class Scenario:
    """One live-join matrix cell: server kind + configs + expectation."""

    id: str
    description: str
    server_kind: str  # "modded" | "vanilla"
    server_config: dict | None
    client_config: dict
    expected: dict
    server_port: int


SCENARIOS = [
    Scenario(
        id="extended-server",
        description="extended Endless server + vanilla client config -> server range wins",
        server_kind="modded",
        server_config=EXTENDED_BUILD_HEIGHT,
        client_config=VANILLA_BUILD_HEIGHT,
        expected=EXTENDED_BUILD_HEIGHT,
        server_port=25575,
    ),
    Scenario(
        id="baseline-endless-vanilla-server",
        description="vanilla-range Endless server + extended client config -> vanilla baseline",
        server_kind="modded",
        server_config=VANILLA_BUILD_HEIGHT,
        client_config=EXTENDED_BUILD_HEIGHT,
        expected=VANILLA_BUILD_HEIGHT,
        server_port=25575,
    ),
    Scenario(
        id="baseline-no-endless",
        description="vanilla server (no Endless) + extended client config -> vanilla baseline",
        server_kind="vanilla",
        server_config=None,
        client_config=EXTENDED_BUILD_HEIGHT,
        expected=VANILLA_BUILD_HEIGHT,
        server_port=25576,
    ),
]


class OutputPump:
    def __init__(self, process: subprocess.Popen[str], prefix: str) -> None:
        self.process = process
        self.prefix = prefix
        self.lines: queue.Queue[str] = queue.Queue()
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            print(f"[{self.prefix}] {line}", end="", flush=True)
            self.lines.put(line)

    def wait_for(self, markers: tuple[str, ...], timeout: int) -> str | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None and self.lines.empty():
                return None
            try:
                line = self.lines.get(timeout=min(1.0, deadline - time.monotonic()))
            except queue.Empty:
                continue
            if any(marker in line for marker in markers):
                return line
        return None


def command(root: Path, task: str) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(wrapper),
        task,
        "--no-daemon",
        "--console=plain",
        "--max-workers=4",
        "-Dorg.gradle.jvmargs=-Xmx2048m",
    ]


def popen(cmd: list[str], root: Path, env: dict[str, str] | None = None) -> subprocess.Popen[str]:
    kwargs: dict[str, object] = {
        "cwd": root,
        "stdin": subprocess.PIPE,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.STDOUT,
        "text": True,
        "bufsize": 1,
    }
    if env is not None:
        kwargs["env"] = env
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(cmd, **kwargs)  # type: ignore[arg-type]


def stop_tree(process: subprocess.Popen[str], graceful_server: bool = False) -> None:
    if process.poll() is not None:
        return
    if graceful_server and process.stdin is not None:
        try:
            process.stdin.write("stop\n")
            process.stdin.flush()
            process.wait(timeout=15)
            return
        except (BrokenPipeError, subprocess.TimeoutExpired):
            pass
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=10)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                pass


def write_endless_config(config_dir: Path, build_height: dict[str, int]) -> None:
    config_dir.mkdir(parents=True, exist_ok=True)
    payload = {"buildHeight": build_height}
    (config_dir / "endless.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )


def reset_dir(path: Path) -> None:
    # Each scenario needs a virgin world and config: a reused live-join world
    # would carry endless_build_heights.dat (and its widened persisted range)
    # from the previous scenario into the next one.
    shutil.rmtree(path, ignore_errors=True)
    path.mkdir(parents=True, exist_ok=True)


def download_vanilla_server(dest: Path, mc_version: str) -> Path:
    """Fetch the official Mojang server jar for a pinned version."""
    jar = dest / f"vanilla-server-{mc_version}.jar"
    if jar.is_file() and jar.stat().st_size > 1_000_000:
        return jar
    manifest_url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    with urllib.request.urlopen(manifest_url, timeout=60) as response:
        manifest = json.load(response)
    entry = next((v for v in manifest["versions"] if v["id"] == mc_version), None)
    if entry is None:
        raise RuntimeError(f"version {mc_version} not found in the Mojang manifest")
    with urllib.request.urlopen(entry["url"], timeout=60) as response:
        version_json = json.load(response)
    url = version_json["downloads"]["server"]["url"]
    last_error: Exception | None = None
    for _ in range(3):
        try:
            with urllib.request.urlopen(url, timeout=120) as response, jar.open("wb") as out:
                shutil.copyfileobj(response, out)
            return jar
        except OSError as error:
            last_error = error
            if jar.is_file():
                jar.unlink()
    raise RuntimeError(f"could not download the vanilla {mc_version} server jar: {last_error}")


def prepare_server(module_dir: Path, scenario: Scenario) -> None:
    server_dir = module_dir / "run" / "live-join" / "server"
    reset_dir(server_dir)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        "online-mode=false\n"
        f"server-port={scenario.server_port}\n"
        "level-name=live-join-world\n"
        f"motd=Endless live join test ({scenario.server_kind})\n"
        "spawn-protection=0\n",
        encoding="utf-8",
    )
    if scenario.server_kind == "vanilla":
        # No Endless mod: the server is the official Mojang jar. The harness
        # records the jar path so run_scenario can launch it with plain java.
        jar = download_vanilla_server(server_dir, MC_VERSION)
        (server_dir / "vanilla-server-jar.txt").write_text(
            str(jar.resolve()), encoding="utf-8")
        return
    # Scenario A uses an extended server-side range; scenario B writes the
    # vanilla range on purpose. The test asserts the client picks up the
    # server's range despite its own (deliberately disagreeing) config.
    write_endless_config(server_dir / "config", scenario.server_config)  # type: ignore[arg-type]


def prepare_client(module_dir: Path, module: str, scenario: Scenario) -> None:
    client_dir = module_dir / "run" / "live-join" / "client"
    reset_dir(client_dir)
    # A fresh Minecraft directory otherwise opens the accessibility/narrator
    # onboarding screen, which blocks quick-play and makes the test interactive.
    (client_dir / "options.txt").write_text(
        "narrator:0\n"
        "narratorHotkey:false\n"
        "onboardAccessibility:false\n"
        "skipMultiplayerWarning:true\n",
        encoding="utf-8",
    )
    write_endless_config(client_dir / "config", scenario.client_config)
    if module == "forge":
        # Forge's early-display window creates its own GL context before the
        # game launches and only reaches GL 4.6/4.5 core profiles; on the CI
        # runner's virtual display it times out ("Timed out trying to setup
        # the Game Window" in fmlearlydisplay), opens an unanswerable console
        # dialog, and kills the client. Disabling early window control defers
        # window creation to Minecraft's own GLFW path, which works there
        # (Fabric's client joins successfully on the same runner).
        (client_dir / "config" / "fml.toml").write_text(
            "earlyWindowControl = false\n", encoding="utf-8"
        )


def run_scenario(root: Path, target: str, module: str, scenario: Scenario, timeout: int) -> None:
    label = f"{target}/{scenario.id}"
    print(f"Preparing {label}: {scenario.description}", flush=True)
    prepare_server(root / module, scenario)
    prepare_client(root / module, module, scenario)

    compile_cmd = command(root, f":{module}:classes")
    subprocess.run(compile_cmd, cwd=root, check=True)

    env = dict(os.environ)
    env["ENDLESS_TEST_EXPECTED_MIN"] = str(scenario.expected["minBuildHeight"])
    env["ENDLESS_TEST_EXPECTED_MAX"] = str(scenario.expected["maxBuildHeight"])
    env["ENDLESS_TEST_PORT"] = str(scenario.server_port)

    if scenario.server_kind == "vanilla":
        server_dir = root / module / "run" / "live-join" / "server"
        jar = Path((server_dir / "vanilla-server-jar.txt").read_text(encoding="utf-8"))
        java = shutil.which("java")
        if java is None:
            raise RuntimeError("java not found on PATH for the vanilla server")
        server = popen(
            [java, "-Xmx1536m", "-jar", jar, "nogui"],
            server_dir,
        )
    else:
        server = popen(command(root, f":{module}:runLiveJoinTestServer"), root)
    server_output = OutputPump(server, f"{label}/server")
    client: subprocess.Popen[str] | None = None
    try:
        if server_output.wait_for(SERVER_READY_MARKERS, timeout) is None:
            raise RuntimeError(f"{label}: server did not become ready")

        client_cmd = command(root, f":{module}:runLiveJoinTestClient")
        if os.name != "nt" and not os.environ.get("DISPLAY"):
            xvfb = shutil.which("xvfb-run")
            if xvfb is None:
                raise RuntimeError("DISPLAY is unset and xvfb-run is not installed")
            client_cmd = [xvfb, "-a", *client_cmd]

        client = popen(client_cmd, root, env=env)
        client_output = OutputPump(client, f"{label}/client")
        outcome = client_output.wait_for(
            (PASS_MARKER, FAIL_MARKER, PRE_LOGIN_FAIL_MARKER), timeout)
        if outcome is None:
            raise RuntimeError(f"{label}: client did not report a live-join outcome")
        if PASS_MARKER in outcome:
            print(f"{label}: PASS ({outcome.rstrip()})", flush=True)
        else:
            raise RuntimeError(f"{label}: client reported failure: {outcome.rstrip()}")
        try:
            exit_code = client.wait(timeout=60)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"{label}: client passed but did not exit") from exc
        if exit_code != 0:
            raise RuntimeError(f"{label}: client exited with code {exit_code} after passing")
    finally:
        if client is not None:
            stop_tree(client)
        stop_tree(server, graceful_server=True)


def run_target(root: Path, target: str, timeout: int) -> None:
    module = TARGETS[target]
    for scenario in SCENARIOS:
        run_scenario(root, target, module, scenario, timeout)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=TARGETS, action="append")
    parser.add_argument("--scenario", choices=[s.id for s in SCENARIOS], action="append")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    targets = args.target or list(TARGETS)
    scenarios = [s for s in SCENARIOS if args.scenario is None or s.id in args.scenario]
    for target in targets:
        for scenario in scenarios:
            run_scenario(root, target, TARGETS[target], scenario, args.timeout)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"LIVE JOIN TEST FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
