"""Verify Create redirects in the shipped Forge jar use Minecraft's runtime SRG names."""
import argparse
import json
from pathlib import Path
import zipfile

PREFIX = "com/nstut/endless/mixin/compat/"
EXPECTED = {
    "CreateArmBlockEntityMixin": {
        "Lnet/minecraft/world/level/Level;getMinBuildHeight()I": "Lnet/minecraft/world/level/Level;m_141937_()I",
        "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I": "Lnet/minecraft/world/level/Level;m_151558_()I",
    },
    "CreatePulleyBlockEntityMixin": {
        "Lnet/minecraft/world/level/Level;getMinBuildHeight()I": "Lnet/minecraft/world/level/Level;m_141937_()I",
    },
    "CreateElevatorColumnMixin": {
        "Lnet/minecraft/core/BlockPos;betweenClosedStream(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/util/stream/Stream;":
            "Lnet/minecraft/core/BlockPos;m_121990_(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/util/stream/Stream;",
    },
    "CreateContraptionMixin": {
        "Lnet/minecraft/core/BlockPos;of(J)Lnet/minecraft/core/BlockPos;":
            "Lnet/minecraft/core/BlockPos;m_122022_(J)Lnet/minecraft/core/BlockPos;",
    },
}


def check_jar(path: Path) -> list[str]:
    mappings = {}
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if name.endswith("refmap.json"):
                mappings.update(json.loads(jar.read(name)).get("mappings", {}))
    errors = []
    for mixin, selectors in EXPECTED.items():
        entries = mappings.get(PREFIX + mixin, {})
        for selector, runtime in selectors.items():
            if entries.get(selector) != runtime:
                errors.append(f"{mixin}: shipped selector {selector} must map to {runtime}; got {entries.get(selector)!r}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    errors = check_jar(args.jar)
    if errors:
        print("\n".join(errors))
        return 1
    print("Create packaged Forge refmap OK: all five Minecraft redirects use runtime SRG selectors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
