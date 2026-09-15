#!/usr/bin/env python3
"""Launch real dedicated servers and auto-joining clients for Endless.

Three scenarios close the server-authority model in both directions:

A. extended-server â€” the server is configured with an extended build range
   ([-4096, 4096)) and the client with the vanilla range ([-64, 320)). The
   client must adopt the server's authoritative range.
B. baseline-endless-vanilla-server â€” an Endless server whose world range is
   vanilla with a client whose local config is extended. The server
   deliberately sends no login query for a vanilla range, so the client must
   enter the world on the vanilla baseline instead of its extended config.
C. baseline-no-endless â€” a genuine vanilla server (official Mojang jar, no
   Endless) with a client whose local config is extended. The client is also
   deliberately pre-seeded with applied=true and an extended effective range
   before connecting. Neither loader's Endless login exchange runs, so the
   every-connection constructor reset must restore the vanilla baseline even
   on this online-mode=false server.

In every scenario a successful join prints ENDLESS_LIVE_JOIN_TEST_PASS; a
failed one prints ENDLESS_LIVE_JOIN_TEST_FAIL.

Used by .github/workflows/live-join-test.yml to detect regressions in the
login-phase height sync on both loaders before the PR merges.
A compile-passing build that fails this test would still be rejected.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
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
CLIENT_OUTCOME_MARKERS = (PASS_MARKER, FAIL_MARKER, PRE_LOGIN_FAIL_MARKER)
SERVER_READY_MARKERS = ("Done (", "For help, type \"help\"")
SERVER_FATAL_MARKERS = (
    "ENDLESS_HIGH_Y_SERVER_FAIL",
    "ENDLESS_FAR_ENVELOPE_FAIL",
    "ENDLESS_COLD_RESTART_FAIL",
    "Encountered an unexpected exception",
    "This crash report has been saved to:",
    "Failed to start the minecraft server",
    "Critical injection failure",
    "Exception generating new chunk",
    "BUILD FAILED",
)
DEFAULT_TIMEOUT = 360

# Shared config presets. The client's on-disk config deliberately disagrees
# with the expectation so that a leaked local config is caught.
EXTENDED_BUILD_HEIGHT = {"minBuildHeight": -4096, "maxBuildHeight": 4096}
FAR_BUILD_HEIGHT = {"minBuildHeight": -8_000_000, "maxBuildHeight": 8_000_000}
MILLION_BUILD_HEIGHT = {"minBuildHeight": -1_048_576, "maxBuildHeight": 1_048_576}
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
    required_server_markers: tuple[str, ...] = ()
    required_client_markers: tuple[str, ...] = ()
    cold_restart: bool = False
    gameplay: bool = False


SCENARIOS = [
    Scenario(
        id="extended-server",
        description="extended Endless server + vanilla client config -> server range wins",
        server_kind="modded",
        server_config=EXTENDED_BUILD_HEIGHT,
        client_config=VANILLA_BUILD_HEIGHT,
        expected=EXTENDED_BUILD_HEIGHT,
        server_port=25575,
        gameplay=True,
        required_server_markers=(
            "ENDLESS_COMMAND_BOUNDS_PASS",
            "ENDLESS_WAYSTONES_SPARSE_PASS",
            "ENDLESS_PATHFINDING_PASS",
            "ENDLESS_CLIENT_INTERACTION_SERVER_PASS",
            "ENDLESS_HIGH_Y_SERVER_PASS",
        ),
        required_client_markers=(
            "ENDLESS_CLIENT_PREDICTION_PASS edge=lower",
            "ENDLESS_CLIENT_PREDICTION_PASS edge=upper",
            "ENDLESS_RENDER_PATH_PASS edge=lower",
            "ENDLESS_RENDER_PATH_PASS edge=upper",
        ),
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
        description="vanilla server (no Endless) + stale extended state -> vanilla baseline",
        server_kind="vanilla",
        server_config=None,
        client_config=EXTENDED_BUILD_HEIGHT,
        expected=VANILLA_BUILD_HEIGHT,
        server_port=25576,
    ),
    Scenario(
        id="far-envelope",
        description="full sparse representation envelope smoke without logical-height scans",
        server_kind="modded",
        server_config=FAR_BUILD_HEIGHT,
        client_config=VANILLA_BUILD_HEIGHT,
        expected=FAR_BUILD_HEIGHT,
        server_port=25577,
        required_server_markers=("ENDLESS_FAR_ENVELOPE_PASS",),
    ),
    Scenario(
        id="cold-restart",
        description="fresh dedicated-server JVM reloads sparse persisted world state at +/-8M",
        server_kind="modded",
        server_config=FAR_BUILD_HEIGHT,
        client_config=VANILLA_BUILD_HEIGHT,
        expected=FAR_BUILD_HEIGHT,
        server_port=25578,
        cold_restart=True,
    ),
]

# Reuse every gameplay assertion, including real client prediction and render
# routing, at million scale. Keep the fast +/-8M representation smoke separate.
SCENARIOS.append(Scenario(
    id="million-gameplay",
    description="real client placement/break, prediction, render routing and mechanics at +/-1M",
    server_kind="modded",
    server_config=MILLION_BUILD_HEIGHT,
    client_config=VANILLA_BUILD_HEIGHT,
    expected=MILLION_BUILD_HEIGHT,
    server_port=25579,
    required_server_markers=SCENARIOS[0].required_server_markers,
    required_client_markers=SCENARIOS[0].required_client_markers,
    gameplay=True,
))


@contextmanager
def checkout_lock(root: Path):
    """Do not let another run erase this run's live worlds or evidence."""
    lock_path = root / "build" / "live-join.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as lock:
        lock.write(b"0")
        lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise RuntimeError("another live verification run owns this checkout") from exc
        try:
            yield
        finally:
            if os.name == "nt":
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def verify_receipts(directory: Path, head: str) -> None:
    expected = {f"{target}--{scenario.id}.pass" for target in TARGETS for scenario in SCENARIOS}
    actual = {p.name for p in directory.glob("*.pass")}
    if expected != actual:
        raise RuntimeError(f"scenario receipts differ: missing={sorted(expected-actual)} extra={sorted(actual-expected)}")
    for name in sorted(expected):
        if (directory / name).read_text(encoding="utf-8").strip() != head:
            raise RuntimeError(f"stale scenario receipt: {name}")


class OutputPump:
    def __init__(self, process: subprocess.Popen[str], prefix: str, log_path: Path | None = None) -> None:
        self.process = process
        self.prefix = prefix
        self.lines: queue.Queue[str] = queue.Queue()
        self.history: list[str] = []
        self.log_path = log_path
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        log = self.log_path.open("w", encoding="utf-8") if self.log_path else None
        try:
            for line in self.process.stdout:
                if log:
                    log.write(line)
                    log.flush()
                print(f"[{self.prefix}] {line}", end="", flush=True)
                self.history.append(line)
                self.lines.put(line)
        finally:
            if log:
                log.close()

    def wait_for(
        self,
        markers: tuple[str, ...],
        timeout: int,
        fail_markers: tuple[str, ...] = (),
    ) -> str | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.exhausted():
                return None
            try:
                line = self.lines.get(timeout=max(0.001, min(1.0, deadline - time.monotonic())))
            except queue.Empty:
                continue
            if any(marker in line for marker in fail_markers):
                raise RuntimeError(
                    f"{self.prefix}: process reported failure: {line.rstrip()}"
                )
            if any(marker in line for marker in markers):
                return line
        return None

    def poll_for(self, markers: tuple[str, ...]) -> str | None:
        while True:
            try:
                line = self.lines.get_nowait()
            except queue.Empty:
                return None
            if any(marker in line for marker in markers):
                return line

    def wait_until_seen(
        self, markers: tuple[str, ...], timeout: int, fail_markers: tuple[str, ...] = ()
    ) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            history = list(self.history)
            failure = next((line for line in history if any(m in line for m in fail_markers)), None)
            if failure is not None:
                raise RuntimeError(f"{self.prefix}: process reported failure: {failure.rstrip()}")
            missing = [marker for marker in markers if not any(marker in line for line in history)]
            if not missing:
                return
            if self.process.poll() is not None and not self.thread.is_alive():
                break
            time.sleep(0.1)
        missing = [marker for marker in markers if not any(marker in line for line in self.history)]
        raise RuntimeError(f"{self.prefix}: missing required marker(s): {missing}")

    def exhausted(self) -> bool:
        return (
            self.process.poll() is not None
            and not self.thread.is_alive()
            and self.lines.empty()
        )


def wait_for_live_join_outcome(
    client_output: OutputPump,
    server_output: OutputPump,
    timeout: int,
    label: str,
) -> str | None:
    """Wait for the client result while failing immediately on server crashes."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        server_failure = server_output.poll_for(SERVER_FATAL_MARKERS)
        if server_failure is not None:
            raise RuntimeError(
                f"{label}: server reported failure: {server_failure.rstrip()}"
            )

        outcome = client_output.poll_for(CLIENT_OUTCOME_MARKERS)
        if outcome is not None:
            return outcome

        if server_output.exhausted():
            raise RuntimeError(
                f"{label}: server exited before the client reported an outcome"
            )
        if client_output.exhausted():
            return None

        time.sleep(0.1)
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
        "spawn-protection=0\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "allow-flight=true\n",
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
        "skipMultiplayerWarning:true\n"
        "renderDistance:4\n"
        "simulationDistance:4\n"
        "maxFps:60\n",
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


def scenario_env(scenario: Scenario, cold_phase: str = "") -> dict[str, str]:
    env = dict(os.environ)
    env["ENDLESS_TEST_EXPECTED_MIN"] = str(scenario.expected["minBuildHeight"])
    env["ENDLESS_TEST_EXPECTED_MAX"] = str(scenario.expected["maxBuildHeight"])
    env["ENDLESS_TEST_PORT"] = str(scenario.server_port)
    env["ENDLESS_TEST_PRESEED_STALE"] = "true" if scenario.id == "baseline-no-endless" else "false"
    env["ENDLESS_TEST_EXTREME"] = "true" if scenario.gameplay else "false"
    env["ENDLESS_TEST_WAYSTONES"] = "true" if scenario.gameplay else "false"
    env["ENDLESS_TEST_FAR"] = "true" if scenario.id == "far-envelope" else "false"
    env["ENDLESS_TEST_COLD_RESTART_PHASE"] = cold_phase
    return env


def run_live_session(
    root: Path, target: str, module: str, scenario: Scenario, timeout: int, env: dict[str, str],
    required_server_markers: tuple[str, ...] = (), required_client_markers: tuple[str, ...] = (),
) -> None:
    label = f"{target}/{scenario.id}" + (f"/phase-{env['ENDLESS_TEST_COLD_RESTART_PHASE']}" if env.get("ENDLESS_TEST_COLD_RESTART_PHASE") else "")
    evidence = root / "build" / "live-join-evidence" / label
    evidence.mkdir(parents=True, exist_ok=True)
    if scenario.server_kind == "vanilla":
        server_dir = root / module / "run" / "live-join" / "server"
        jar = Path((server_dir / "vanilla-server-jar.txt").read_text(encoding="utf-8"))
        java = shutil.which("java")
        if java is None:
            raise RuntimeError("java not found on PATH for the vanilla server")
        server = popen([java, "-Xmx1536m", "-jar", jar, "nogui"], server_dir, env=env)
    else:
        server = popen(command(root, f":{module}:runLiveJoinTestServer"), root, env=env)
    server_output = OutputPump(server, f"{label}/server", evidence / "server.log")
    client: subprocess.Popen[str] | None = None
    try:
        if server_output.wait_for(SERVER_READY_MARKERS, timeout, fail_markers=SERVER_FATAL_MARKERS) is None:
            raise RuntimeError(f"{label}: server did not become ready")

        client_cmd = command(root, f":{module}:runLiveJoinTestClient")
        if os.name != "nt" and not os.environ.get("DISPLAY"):
            xvfb = shutil.which("xvfb-run")
            if xvfb is None:
                raise RuntimeError("DISPLAY is unset and xvfb-run is not installed")
            client_cmd = [xvfb, "-a", *client_cmd]

        client = popen(client_cmd, root, env=env)
        client_output = OutputPump(client, f"{label}/client", evidence / "client.log")
        outcome = wait_for_live_join_outcome(client_output, server_output, timeout, label)
        if outcome is None:
            raise RuntimeError(f"{label}: client did not report a live-join outcome")
        if PASS_MARKER not in outcome:
            raise RuntimeError(f"{label}: client reported failure: {outcome.rstrip()}")

        if required_server_markers:
            server_output.wait_until_seen(required_server_markers, min(timeout, 90), SERVER_FATAL_MARKERS)
        if required_client_markers:
            client_output.wait_until_seen(required_client_markers, min(timeout, 30), (FAIL_MARKER, PRE_LOGIN_FAIL_MARKER))
        print(f"{label}: PASS ({outcome.rstrip()})", flush=True)
        stop_tree(client)
        client = None
    finally:
        if client is not None:
            stop_tree(client)
        stop_tree(server, graceful_server=True)


def _run_scenario(root: Path, target: str, module: str, scenario: Scenario, timeout: int) -> None:
    label = f"{target}/{scenario.id}"
    print(f"Preparing {label}: {scenario.description}", flush=True)
    prepare_server(root / module, scenario)
    prepare_client(root / module, module, scenario)

    env = scenario_env(scenario, "A" if scenario.cold_restart else "")
    compile_cmd = command(root, f":{module}:classes")
    subprocess.run(compile_cmd, cwd=root, env=env, check=True, timeout=max(timeout, 600))

    if not scenario.cold_restart:
        run_live_session(
            root, target, module, scenario, timeout, env,
            scenario.required_server_markers, scenario.required_client_markers,
        )
        return

    # Phase A saves and gracefully stops. Phase B deliberately reuses the same
    # world directory but starts a brand-new dedicated-server JVM.
    run_live_session(root, target, module, scenario, timeout, env, ("ENDLESS_COLD_RESTART_PHASE_A_PASS",))
    prepare_client(root / module, module, scenario)
    phase_b_env = scenario_env(scenario, "B")
    run_live_session(root, target, module, scenario, timeout, phase_b_env, ("ENDLESS_COLD_RESTART_PHASE_B_PASS",))


def run_scenario(root: Path, target: str, module: str, scenario: Scenario, timeout: int) -> None:
    evidence = root / "build" / "live-join-evidence" / target / scenario.id
    evidence.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    result = {
        "target": target, "scenario": scenario.id,
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip()),
        "expected": scenario.expected, "status": "running",
        "required_server_markers": scenario.required_server_markers,
        "required_client_markers": scenario.required_client_markers,
    }
    try:
        _run_scenario(root, target, module, scenario, timeout)
        result["status"] = "pass"
    except BaseException as exc:
        result.update(status="fail", error=str(exc))
        raise
    finally:
        result["elapsed_seconds"] = round(time.monotonic() - started, 3)
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        # Preserve diagnostics before the next scenario recreates its run dirs.
        for role in ("client", "server"):
            source = root / module / "run" / "live-join" / role
            for folder in ("logs", "crash-reports"):
                if (source / folder).is_dir():
                    shutil.copytree(source / folder, evidence / role / folder, dirs_exist_ok=True)


def run_target(root: Path, target: str, timeout: int) -> None:
    module = TARGETS[target]
    for scenario in SCENARIOS:
        run_scenario(root, target, module, scenario, timeout)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=TARGETS, action="append")
    parser.add_argument("--scenario", choices=[s.id for s in SCENARIOS], action="append")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT)
    parser.add_argument("--matrix", action="store_true", help="print the canonical CI matrix without launching Minecraft")
    parser.add_argument("--verify-receipts", type=Path)
    parser.add_argument("--head")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    if args.matrix:
        print(json.dumps({"target": list(TARGETS), "scenario": [s.id for s in SCENARIOS]}))
        return 0
    if args.verify_receipts:
        if not args.head:
            parser.error("--verify-receipts requires --head")
        verify_receipts(args.verify_receipts, args.head)
        return 0
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    targets = args.target or list(TARGETS)
    scenarios = [s for s in SCENARIOS if args.scenario is None or s.id in args.scenario]
    failures = []
    with checkout_lock(root):
        for target in targets:
            for scenario in scenarios:
                try:
                    run_scenario(root, target, TARGETS[target], scenario, args.timeout)
                except (RuntimeError, subprocess.SubprocessError, OSError) as exc:
                    failures.append(f"{target}/{scenario.id}: {exc}")
                    print(f"LIVE JOIN TEST FAILED: {failures[-1]}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"LIVE JOIN TEST FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
