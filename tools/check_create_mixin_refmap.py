"""Verify Create redirects in the shipped Forge jar use Minecraft's runtime SRG names."""
import argparse
import json
from pathlib import Path
import zipfile

PREFIX = "com/nstut/endless/mixin/compat/"
EXPECTED = {
    "PistonBaseBlockMixin": {
        "Lnet/minecraft/world/level/Level;getMinBuildHeight()I": "Lnet/minecraft/world/level/Level;m_141937_()I",
        "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I": "Lnet/minecraft/world/level/Level;m_151558_()I",
    },
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
    "CreateTrackNodeLocationMixin": {
        "Lnet/minecraft/network/FriendlyByteBuf;writeShort(I)Lio/netty/buffer/ByteBuf;":
            "Lnet/minecraft/network/FriendlyByteBuf;writeShort(I)Lio/netty/buffer/ByteBuf;",
        "Lnet/minecraft/network/FriendlyByteBuf;writeVarInt(I)Lnet/minecraft/network/FriendlyByteBuf;":
            "Lnet/minecraft/network/FriendlyByteBuf;m_130130_(I)Lnet/minecraft/network/FriendlyByteBuf;",
        "(III)Lnet/minecraft/core/BlockPos;": "(III)Lnet/minecraft/core/BlockPos;",
    },
    "CreateChorusTeleportMixin": {
        "Lnet/minecraft/util/Mth;clamp(DDD)D": "Lnet/minecraft/util/Mth;m_14008_(DDD)D",
    },
}


def mixin_key(name: str) -> str:
    return ("com/nstut/endless/mixin/" if name == "PistonBaseBlockMixin" else PREFIX) + name


def check_jar(path: Path) -> list[str]:
    mappings = {}
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if name.endswith("refmap.json"):
                mappings.update(json.loads(jar.read(name)).get("mappings", {}))
    errors = []
    for mixin, selectors in EXPECTED.items():
        entries = mappings.get(mixin_key(mixin), {})
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
    print("Create packaged Forge refmap OK: all required Minecraft selectors use runtime mappings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
