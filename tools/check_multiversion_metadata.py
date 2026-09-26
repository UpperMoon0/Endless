#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


PROPS = properties(ROOT / "gradle.properties")
MOD_VERSION = PROPS["mod_version"]
VERSION_PLACEHOLDER = "$" + "{version}"

TARGETS = (
    {
        "module": "fabric-1.20.1",
        "platform": "fabric",
        "mc": PROPS["minecraft_version_1_20_1"],
        "resource": "fabric.mod.json",
        "java": "17",
        "loader": PROPS["fabric_loader_version_1_20_1"],
    },
    {
        "module": "forge-1.20.1",
        "platform": "forge",
        "mc": PROPS["minecraft_version_1_20_1"],
        "resource": "META-INF/mods.toml",
        "java": "17",
    },
    {
        "module": "fabric-1.21.1",
        "platform": "fabric",
        "mc": PROPS["minecraft_version_1_21_1"],
        "resource": "fabric.mod.json",
        "java": "21",
        "loader": PROPS["fabric_loader_version_1_21_1"],
    },
    {
        "module": "neoforge-1.21.1",
        "platform": "neoforge",
        "mc": PROPS["minecraft_version_1_21_1"],
        "resource": "META-INF/neoforge.mods.toml",
        "java": "21",
        "mc_range": f'[{PROPS["minecraft_version_1_21_1"]},1.22)',
    },
    {
        "module": "neoforge-26.1.2",
        "platform": "neoforge",
        "mc": PROPS["minecraft_version_26_1_2"],
        "resource": "META-INF/neoforge.mods.toml",
        "java": "25",
        "mc_range": PROPS["minecraft_version_range_26_1_2"],
    },
)

RUN_TASKS = {
    "fabric-1.20.1": ("runFabric1201Client", "runFabric1201Server"),
    "forge-1.20.1": ("runForge1201Client", "runForge1201Server"),
    "fabric-1.21.1": ("runFabric1211Client", "runFabric1211Server"),
    "neoforge-1.21.1": ("runNeoForge1211Client", "runNeoForge1211Server"),
    "neoforge-26.1.2": ("runNeoForge2612Client", "runNeoForge2612Server"),
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"metadata drift: {message}")


settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
build = (ROOT / "build.gradle").read_text(encoding="utf-8")
release = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
readme = (ROOT / "README.md").read_text(encoding="utf-8")

expected_by_version = {
    "1_20_1": {"fabric", "forge"},
    "1_21_1": {"fabric", "neoforge"},
    "26_1_2": {"neoforge"},
}
for suffix, expected in expected_by_version.items():
    actual = {p.strip() for p in PROPS[f"enabled_platforms_{suffix}"].split(",") if p.strip()}
    require(actual == expected, f"enabled_platforms_{suffix}={sorted(actual)} expected {sorted(expected)}")

for target in TARGETS:
    module = target["module"]
    platform = target["platform"]
    mc = target["mc"]
    require(f"'{module}'" in settings, f"{module} is missing from settings.gradle")
    for task in RUN_TASKS[module]:
        require(task in build, f"{task} is missing from the root run-task matrix")
    require(f"project: {module}" in release, f"{module} is missing from the release build matrix")
    require(mc in readme, f"README does not mention supported Minecraft {mc}")

    resource = ROOT / module / "src/main/resources" / target["resource"]
    require(resource.is_file(), f"{module} metadata is missing: {resource.relative_to(ROOT)}")
    text = resource.read_text(encoding="utf-8")
    require(VERSION_PLACEHOLDER in text, f"{module} metadata must use the expanded version placeholder")

    if platform == "fabric":
        data = json.loads(text)
        require(data.get("id") == "endless", f"{module} fabric id is not endless")
        depends = data.get("depends", {})
        require(depends.get("minecraft") == f"={mc}", f"{module} Minecraft dependency drifted: {depends.get('minecraft')!r}")
        require(depends.get("java") == f">={target['java']}", f"{module} Java dependency drifted: {depends.get('java')!r}")
        require(depends.get("fabricloader") == f">={target['loader']}", f"{module} Fabric Loader dependency drifted")
        require("endless.mixins.json" in data.get("mixins", []), f"{module} does not declare endless.mixins.json")
    elif platform == "forge":
        require(f'versionRange = "[{mc}]"' in text, f"{module} Minecraft versionRange drifted from [{mc}]")
        require('modId = "forge"' in text, f"{module} Forge dependency is missing")
    else:
        mc_range = target["mc_range"]
        require(f'versionRange = "{mc_range}"' in text, f"{module} Minecraft versionRange drifted from {mc_range}")
        require('modId = "neoforge"' in text, f"{module} NeoForge dependency is missing")
        require('config = "endless.mixins.json"' in text, f"{module} does not declare endless.mixins.json")

changelog = ROOT / "changelogs" / f"v{MOD_VERSION}.txt"
require(changelog.is_file() and changelog.stat().st_size > 0, f"missing changelog for mod_version={MOD_VERSION}")

# API-identical version sources belong in one of the shared source roots. Catch
# future copy/paste drift before it can reproduce fixes unevenly across ports.
version_trees = {
    "1.20.1": ROOT / "common-1.20.1/src/main/java",
    "1.21.1": ROOT / "common-1.21.1/src/main/java",
    "26.1.2": ROOT / "neoforge-26.1.2/src/main/java",
}
seen_sources: dict[tuple[str, str], str] = {}
for version, tree in version_trees.items():
    for source in tree.rglob("*.java"):
        relative = source.relative_to(tree).as_posix()
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        key = (relative, digest)
        previous = seen_sources.get(key)
        require(
            previous is None,
            f"exact duplicate version source {relative} exists in both {previous} and {version}; move it to a shared source root",
        )
        seen_sources[key] = version

test_trees = {
    "1.20.1": ROOT / "common-1.20.1/src/test/java",
    "1.21.1": ROOT / "common-1.21.1/src/test/java",
}
seen_tests: dict[tuple[str, str], str] = {}
for version, tree in test_trees.items():
    for source in tree.rglob("*.java"):
        relative = source.relative_to(tree).as_posix()
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        key = (relative, digest)
        previous = seen_tests.get(key)
        require(
            previous is None,
            f"exact duplicate version test {relative} exists in both {previous} and {version}; move it to a shared test source root",
        )
        seen_tests[key] = version

print(f"multiversion metadata OK: {len(TARGETS)} release targets, mod_version={MOD_VERSION}; version sources deduplicated")
