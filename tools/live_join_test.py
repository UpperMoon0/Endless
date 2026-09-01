#!/usr/bin/env python3
"""Launch a real dedicated server and auto-joining client for Endless.

The server is configured with an extended build range ([-1024, 1024)) and the
client is configured with the vanilla range ([-64, 320)). The client-side mod
verifies that, after joining, its effective range and ClientLevel range match
the server's authoritative range. A successful join prints
ENDLESS_LIVE_JOIN_TEST_PASS; a failed one prints ENDLESS_LIVE_JOIN_TEST_FAIL.

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
from pathlib import Path


PASS_MARKER = "ENDLESS_LIVE_JOIN_TEST_PASS"
FAIL_MARKER = "ENDLESS_LIVE_JOIN_TEST_FAIL"
# Printed at ClientboundLoginPacket handling, before the client world exists.
# A pre-login failure also prints FAIL_MARKER, but matching it here fails the
# test immediately instead of waiting for the post-join timeout.
PRE_LOGIN_FAIL_MARKER = "ENDLESS_PRE_LOGIN_RANGE_FAIL"
SERVER_READY_MARKERS = ("Done (", "For help, type \"help\"")
DEFAULT_TIMEOUT = 360

# Server-side config the test server uses. The client-side test mod asserts
# the client ends up with these bounds after joining.
SERVER_BUILD_HEIGHT = {"minBuildHeight": -1024, "maxBuildHeight": 1024}

# Client-side config the test client uses on disk; the server's range must
# win after the login/play-phase sync, so the client deliberately disagrees.
CLIENT_BUILD_HEIGHT = {"minBuildHeight": -64, "maxBuildHeight": 320}

TARGETS = {
    "fabric-1.20.1": "fabric",
    "forge-1.20.1": "forge",
}


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


def popen(cmd: list[str], root: Path) -> subprocess.Popen[str]:
    kwargs: dict[str, object] = {
        "cwd": root,
        "stdin": subprocess.PIPE,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.STDOUT,
        "text": True,
        "bufsize": 1,
    }
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
            except ProcessLookupError:
                pass


def write_endless_config(config_dir: Path, build_height: dict[str, int]) -> None:
    config_dir.mkdir(parents=True, exist_ok=True)
    payload = {"buildHeight": build_height}
    (config_dir / "endless.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )


def prepare_server(module_dir: Path) -> None:
    server_dir = module_dir / "run" / "live-join" / "server"
    server_dir.mkdir(parents=True, exist_ok=True)
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text(
        "online-mode=false\n"
        "server-port=25575\n"
        "level-name=live-join-world\n"
        "motd=Endless live join test\n"
        "spawn-protection=0\n",
        encoding="utf-8",
    )
    # Extended server-side range. The test asserts the client picks this up
    # despite its own (vanilla) local config.
    write_endless_config(server_dir / "config", SERVER_BUILD_HEIGHT)


def prepare_client(module_dir: Path, module: str) -> None:
    client_dir = module_dir / "run" / "live-join" / "client"
    client_dir.mkdir(parents=True, exist_ok=True)
    # A fresh Minecraft directory otherwise opens the accessibility/narrator
    # onboarding screen, which blocks quick-play and makes the test interactive.
    (client_dir / "options.txt").write_text(
        "narrator:0\n"
        "narratorHotkey:false\n"
        "onboardAccessibility:false\n"
        "skipMultiplayerWarning:true\n",
        encoding="utf-8",
    )
    # Vanilla client-side range; the server's range must win after sync.
    write_endless_config(client_dir / "config", CLIENT_BUILD_HEIGHT)
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


def run_target(root: Path, target: str, timeout: int) -> None:
    module = TARGETS[target]
    print(f"Preparing {target} live join test", flush=True)
    prepare_server(root / module)
    prepare_client(root / module, module)

    compile_cmd = command(root, f":{module}:classes")
    subprocess.run(compile_cmd, cwd=root, check=True)

    server = popen(command(root, f":{module}:runLiveJoinTestServer"), root)
    server_output = OutputPump(server, f"{target}/server")
    client: subprocess.Popen[str] | None = None
    try:
        if server_output.wait_for(SERVER_READY_MARKERS, timeout) is None:
            raise RuntimeError(f"{target}: server did not become ready")

        client_cmd = command(root, f":{module}:runLiveJoinTestClient")
        if os.name != "nt" and not os.environ.get("DISPLAY"):
            xvfb = shutil.which("xvfb-run")
            if xvfb is None:
                raise RuntimeError("DISPLAY is unset and xvfb-run is not installed")
            client_cmd = [xvfb, "-a", *client_cmd]

        client = popen(client_cmd, root)
        client_output = OutputPump(client, f"{target}/client")
        outcome = client_output.wait_for(
            (PASS_MARKER, FAIL_MARKER, PRE_LOGIN_FAIL_MARKER), timeout)
        if outcome is None:
            raise RuntimeError(f"{target}: client did not report a live-join outcome")
        if PASS_MARKER in outcome:
            print(f"{target}: PASS ({outcome.rstrip()})", flush=True)
        else:
            raise RuntimeError(f"{target}: client reported failure: {outcome.rstrip()}")
        try:
            exit_code = client.wait(timeout=60)
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"{target}: client passed but did not exit") from exc
        if exit_code != 0:
            raise RuntimeError(f"{target}: client exited with code {exit_code} after passing")
    finally:
        if client is not None:
            stop_tree(client)
        stop_tree(server, graceful_server=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=TARGETS, action="append")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    targets = args.target or list(TARGETS)
    for target in targets:
        run_target(root, target, args.timeout)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"LIVE JOIN TEST FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
