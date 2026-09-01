# Endless

Endless is a Minecraft 1.20.1 mod for Forge and Fabric that provides a sparse, practically unbounded vertical building space without allocating a dense chunk column for the entire height.

**v0.5 logical build range:** Y=-8,000,000 through Y=7,999,999.

CurseForge: https://www.curseforge.com/minecraft/mc-mods/nstut-endless

![Endless](assets/icon.png)

## How v0.5 works

Minecraft 1.20.1 cannot safely make its normal `LevelChunkSection[]` millions of blocks tall. Vanilla also packs `BlockPos` Y into 12 bits and stores normal chunk section Y as a signed byte. Endless therefore keeps the v0.4 dense core intact and adds a separate sparse vertical engine around it.

- The **dense core** is the normal vanilla-compatible chunk section array. `config/endless.json` still controls this range and it remains guard-bounded to `[-2032, 2032)`.
- Coordinates outside that core, up to the v0.5 logical bounds, are stored in **512-block sparse pages**. Empty height costs no section-array memory.
- Sparse pages use dedicated compressed NBT storage under each dimension instead of vanilla `ChunkSerializer`, so high section Y is never narrowed to a signed byte.
- High-Y `BlockPos` network fields use an Endless protocol extension. Positions that fit vanilla's packed envelope retain the normal vanilla wire encoding.
- The client receives only sparse pages near its current vertical window. The render grid follows the camera and stays 32 sections / 512 blocks tall.
- Sparse heightmaps, high-Y block/fluid access, block entities, POIs, scheduled ticks, neighbor updates, and page-aware lighting are integrated with the normal Level APIs.
- Horizontal chunk unload evicts its sparse pages after flushing dirty data, so visited height does not accumulate forever in memory.

The ±8,000,000 logical range is deliberate. It stays inside Minecraft 1.20.1's signed 20-bit `SectionPos` Y envelope, allowing POI and several section-keyed vanilla systems to remain correct while Endless replaces the much narrower packed `BlockPos` and dense-section assumptions.

## Features

- **Practical Infinite Height** — Build from Y=-8,000,000 to Y=7,999,999.
- **Sparse Memory Use** — Only vertical pages that contain data are allocated or persisted.
- **All Dimensions** — Overworld, Nether, End, and other normal Level dimensions use their own sparse storage.
- **Forge + Fabric** — Same sparse engine and protocol on both loaders.
- **Persistent High-Y Blocks** — Extended pages save independently from Anvil chunk sections and reload lazily.
- **High-Y Fluids and Block Entities** — Level/LevelChunk access is routed through sparse pages while normal block lifecycle callbacks remain active.
- **Heightmap Overlay** — World-surface, ocean-floor, and motion-blocking height queries include sparse blocks.
- **Page-Aware Lighting** — Emissive blocks propagate block light across sparse page boundaries; sky exposure is evaluated against dense + sparse tops without packed-Y wrapping.
- **Vanilla POI/Tick Semantics Where Safe** — The logical range stays inside `SectionPos`, so high-Y POIs and chunk tick containers retain vanilla section addressing.
- **Server-Authoritative Protocol** — v0.5 sparse worlds require an Endless v0.5-compatible client and synchronize logical-height capability during login.
- **Camera-Following Rendering** — A 512-block vertical render window follows the player instead of allocating GPU render chunks for millions of blocks.
- **Fail-Closed Legacy Migration** — The v0.4 migration gate is preserved for old dense-world data.

## Configuration

`config/endless.json` configures the **dense compatibility core**, not the total v0.5 buildable height:

```json
{
  "buildHeight": {
    "minBuildHeight": -64,
    "maxBuildHeight": 320
  }
}
```

The dense core may be widened within `[-2032, 2032)`. Once a v0.4+ world persists a dense range, later config changes may widen it but never shrink it. This preserves old Anvil section layout and prevents saved dense sections from becoming unreachable.

The v0.5 sparse logical range is currently fixed at `[-8000000, 8000000)`. Keeping this separate from the dense core is what makes the large range practical.

### Existing worlds

v0.5 retains v0.4's fail-closed migration rules. Played pre-v0.4 worlds are inspected before any chunk loads. Ambiguous historical section layouts, meaningful data in unsafe guard sections, conflicting heightmap packing, or untrusted migration inputs stop startup instead of allowing vanilla to silently discard sections.

Back up important worlds before upgrading. Sparse v0.5 pages do not reinterpret legacy Anvil data; they are a new storage layer outside the persisted dense core.

## Compatibility notes

Endless supports Minecraft 1.20.1 on Forge and Fabric. v0.5 sparse multiplayer requires Endless on both server and client.

Mods that use ordinary `Level`, `LevelChunk`, `BlockPos`, block entity, tick, POI, heightmap, and brightness APIs can operate at high Y through Endless' routing. A mod that directly converts high-Y positions with `BlockPos.asLong()`, assumes `chunk.getSections()` contains every possible Y, or directly inspects vanilla light `DataLayer` storage can still impose vanilla's old bounds on itself. Those are representation-level assumptions that cannot be transparently fixed inside another mod's private data structures.

## Limitations

- **World generation** remains in the normal generator range. v0.5 adds buildable sparse space; it does not generate terrain millions of blocks high by default.
- **Rendering** covers a 512-block vertical window around the camera. Far-away vertical pages remain saved and active server-side but are not rendered until the camera approaches them.
- **Logical range** is practical rather than mathematical infinity: `[-8,000,000, 8,000,000)`, chosen to preserve vanilla `SectionPos`-keyed systems.

## Development

Build and test:

```bash
./gradlew test
./gradlew build
```

Run one loader explicitly:

```bash
./gradlew runFabricClient
./gradlew runForgeClient
./gradlew runFabricServer
./gradlew runForgeServer
```

The live-join CI matrix starts real Fabric and Forge dedicated servers and clients. Its extended scenario writes blocks, fluid, a block entity and a POI at Y=1,000,000, flushes/reloads sparse storage, checks high-Y height/light state, teleports the client there, and requires the client to observe the reloaded state.

## License

All Rights Reserved

## Author

- NsTut
