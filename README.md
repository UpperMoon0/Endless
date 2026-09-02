# Endless

Endless is a Minecraft 1.20.1 mod for Forge and Fabric that provides a sparse, practically unbounded vertical building space without allocating a dense chunk column for the entire height.

**v0.5 supported configuration envelope:** Y=-8,000,000 through Y=7,999,999.

CurseForge: https://www.curseforge.com/minecraft/mc-mods/nstut-endless

![Endless](assets/icon.png)

## How v0.5 works

Minecraft 1.20.1 cannot safely make its normal `LevelChunkSection[]` millions of blocks tall. Vanilla also packs `BlockPos` Y into 12 bits and stores normal chunk section Y as a signed byte. Endless therefore separates the user-facing logical build range from the vanilla-compatible dense chunk core and stores extended space in sparse pages.

- `config/endless.json` defines the **logical build range** used by placement, commands, teleport validity, AI limits, rendering queries, and sparse routing. Any section-aligned subrange of `[-8000000, 8000000)` is supported.
- A fresh v0.5 world keeps the **dense core** at vanilla `[-64, 320)`. Widening the logical config does not widen `LevelChunkSection[]`.
- Existing/migrated worlds may retain a wider historical dense core (up to the legacy-safe `[-2032, 2032)` envelope) solely so old Anvil sections are never discarded. That internal range does **not** widen the configured build limit.
- Coordinates outside the dense core but inside the configured logical range are stored in **512-block sparse pages**. Empty height costs no section-array memory.
- Sparse pages use dedicated compressed NBT storage under each dimension instead of vanilla `ChunkSerializer`, so high section Y is never narrowed to a signed byte.
- High-Y `BlockPos` network fields use an Endless protocol extension. Positions that fit vanilla's packed envelope retain the normal vanilla wire encoding.
- The client receives only sparse pages near its current vertical window. The render grid follows the camera and stays 32 sections / 512 blocks tall.
- Sparse heightmaps, high-Y block/fluid access, block entities, POIs, scheduled ticks, neighbor updates, and page-aware lighting are integrated with the normal Level APIs.
- Horizontal chunk unload evicts its sparse pages after flushing dirty data, so visited height does not accumulate forever in memory.

The ±8,000,000 representation envelope is deliberate. It stays inside Minecraft 1.20.1's signed 20-bit `SectionPos` Y envelope, allowing POI and several section-keyed vanilla systems to remain correct while Endless replaces the much narrower packed `BlockPos` and dense-section assumptions.

## Features

- **Configurable Practical Infinite Height** — Choose any section-aligned logical build range from Y=-8,000,000 through Y=7,999,999.
- **Strict Configured Bounds** — Normal placement and vanilla commands use the configured logical min/max; the ±8M representation envelope is not automatically buildable.
- **Sparse Memory Use** — Only vertical pages that contain data are allocated or persisted; fresh extended worlds keep vanilla-sized dense section arrays.
- **All Dimensions** — Overworld, Nether, End, and other normal Level dimensions use their own sparse storage.
- **Forge + Fabric** — Same sparse engine and protocol on both loaders.
- **Persistent High-Y Blocks** — Extended pages save independently from Anvil chunk sections and reload lazily.
- **High-Y Fluids and Block Entities** — Level/LevelChunk access is routed through sparse pages while normal block lifecycle callbacks remain active.
- **Heightmap Overlay** — World-surface, ocean-floor, and motion-blocking height queries include sparse blocks.
- **Page-Aware Lighting** — Emissive blocks propagate block light across sparse page boundaries; sky exposure is evaluated against dense + sparse tops without packed-Y wrapping.
- **Sparse POI Support** — High-Y POIs use dedicated sparse persistence/search instead of widening vanilla SectionStorage loops.
- **Server-Authoritative Protocol** — The server synchronizes both its configured logical range and internal dense-core layout before chunk/page data is used.
- **Waystones Compatibility** — Waystones 1.20.1 placement uses the configured logical ceiling and high-Y Waystone block entities/positions travel through the extended storage/network path.
- **Camera-Following Rendering** — A 512-block vertical render window follows the player instead of allocating GPU render chunks for millions of blocks.
- **Fail-Closed Legacy Migration** — The v0.4 migration gate is preserved for old dense-world data.

## Configuration

`config/endless.json` configures the actual **logical build limit**:

```json
{
  "buildHeight": {
    "minBuildHeight": -1024,
    "maxBuildHeight": 1024
  }
}
```

- `minBuildHeight` is inclusive.
- `maxBuildHeight` is exclusive, so the example's highest legal block is Y=1023.
- Values are normalized to 16-block section boundaries and clamped only to the v0.5 representation envelope `[-8000000, 8000000)`.
- Valid million-scale values are preserved across launch; they are not clamped back to the old ±2032 dense envelope.
- Restart after changing the file. The server's configured logical range is authoritative for multiplayer clients.

Changing a logical range can narrow or widen where players, commands, and compatible mods may operate. It does not delete sparse page files outside the newly narrowed range. Existing historical dense sections are also retained internally for Anvil safety, but are inaccessible whenever they lie outside the current configured logical range.

### Existing worlds

Fresh v0.5 worlds keep a vanilla `[-64,320)` dense core. A world upgraded from an older Endless version may have a wider persisted dense core because old releases stored extended sections directly in vanilla Anvil chunks. That persisted dense layout never shrinks automatically.

v0.5 retains v0.4's fail-closed migration rules. Played pre-v0.4 worlds are inspected before any chunk loads. Ambiguous historical section layouts, meaningful data in unsafe guard sections, conflicting heightmap packing, or untrusted migration inputs stop startup instead of allowing vanilla to silently discard sections.

Back up important worlds before upgrading. Sparse v0.5 pages do not reinterpret legacy Anvil data; they are a new storage layer outside the persisted dense core.

## Compatibility notes

Endless supports Minecraft 1.20.1 on Forge and Fabric. Sparse multiplayer requires an Endless v0.5-compatible client.

Mods that use ordinary `Level`, `LevelChunk`, `BlockPos`, block entity, tick, POI, heightmap, and brightness APIs can operate at high Y through Endless' routing. A mod that directly converts high-Y positions with `BlockPos.asLong()`, assumes `chunk.getSections()` contains every possible Y, or directly inspects vanilla light `DataLayer` storage can still impose vanilla's old bounds on itself. Those are representation-level assumptions that cannot be transparently fixed inside another mod's private data structures.

Waystones 1.20.1 is explicitly covered: its placement code normally treats `Level#getHeight()` as an absolute ceiling, while Endless deliberately keeps that accessor dense-core-sized. Endless redirects that Waystones placement check to the configured logical maximum and covers its high-Y block entity/position path in the real client/server compatibility test.

## Limitations

- **World generation** remains in the normal generator range. v0.5 adds buildable sparse space; it does not generate terrain millions of blocks high by default.
- **Rendering** covers a 512-block vertical window around the camera. Far-away vertical pages remain saved and active server-side but are not rendered until the camera approaches them.
- **Representation envelope** is practical rather than mathematical infinity: `[-8,000,000, 8,000,000)`, chosen to preserve vanilla `SectionPos`-keyed systems.

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

The live-join CI matrix starts real Fabric and Forge dedicated servers and clients. The extended scenario uses a configured `[-1024,1024)` logical range over a vanilla-sized dense core, exercises blocks, fluid, block entities, POIs, lighting, persistence, rendering and player travel at both configured sparse edges, executes real `/setblock` commands at and just outside those limits, and loads canonical Waystones+Balm artifacts to verify high-Y Waystone placement/manager/client state.

## License

All Rights Reserved

## Author

- NsTut
