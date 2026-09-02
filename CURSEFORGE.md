Endless v0.5 adds sparse practical infinite build height for Minecraft 1.20.1: configure build limits anywhere from Y=-8,000,000 through Y=7,999,999 without allocating millions of empty chunk sections.

## What changed in v0.5

- **Configurable Logical Build Range** — `config/endless.json` now controls the actual player/command build limits anywhere inside `[-8000000, 8000000)`.
- **Sparse Vertical Pages** — Extended space is stored in 512-block pages and allocated only where blocks exist.
- **Vanilla-Sized Fresh Dense Core** — New v0.5 worlds keep normal `[-64,320)` dense chunk arrays even when the configured build range is thousands or millions of blocks tall.
- **Dedicated High-Y Persistence** — Sparse pages use compressed NBT outside vanilla Anvil section serialization, avoiding signed-byte section-Y truncation.
- **Extended Position Protocol** — High-Y block positions use an Endless client/server encoding while normal positions keep vanilla's packed format.
- **Forge + Fabric** — The same sparse engine and login/page protocol are implemented on both loaders.
- **High-Y Blocks, Fluids and Block Entities** — Normal Level access and block lifecycle callbacks work outside the dense core.
- **Sparse Heightmaps and Lighting** — Supported height queries include sparse pages and block light propagates without packed-Y wrapping.
- **Sparse POIs** — High-Y POIs use dedicated sparse persistence/search instead of widening vanilla SectionStorage loops.
- **Strict Command/Placement Bounds** — The configured min/max, not the ±8M representation ceiling, decides whether a position is legal.
- **Waystones 1.20.1 Compatibility** — Waystone placement uses the configured logical ceiling and its high-Y block entity/position path is covered by real client/server CI.
- **Camera-Following Rendering** — The client renders a 512-block vertical window around the camera instead of allocating render chunks for the whole logical range.
- **Bounded Runtime Memory** — Sparse columns flush and unload with their horizontal chunks.
- **Old-World Safety Preserved** — v0.4's fail-closed dense-world migration gate remains in place.

## Configuration

`config/endless.json` defines the logical build limit:

```json
{
  "buildHeight": {
    "minBuildHeight": -1024,
    "maxBuildHeight": 1024
  }
}
```

`minBuildHeight` is inclusive and `maxBuildHeight` is exclusive. Values are section-aligned and clamped only to the supported `[-8000000, 8000000)` representation envelope. Valid million-scale values survive restart instead of being forced back to the old ±2032 range.

Fresh v0.5 worlds keep a vanilla `[-64,320)` dense core internally. Worlds upgraded from older Endless releases may retain a wider historical dense core, up to the legacy-safe `[-2032,2032)` envelope, only to prevent old Anvil sections from being dropped. That internal compatibility range never widens the configured build limit.

## Multiplayer

Sparse v0.5 worlds require Endless v0.5-compatible clients. The server synchronizes its configured logical range and internal dense layout during login and sends sparse pages near each player's current vertical window.

## Existing worlds

Played pre-v0.4 worlds are still inspected before chunks load. Endless refuses ambiguous or unsafe historical layouts instead of letting vanilla silently discard sections. Back up important worlds before upgrading.

## Compatibility notes

Mods using normal Level/LevelChunk/BlockPos, block entity, POI, tick, heightmap and brightness APIs can work through Endless' routing. Mods that directly call `BlockPos.asLong()` on high-Y positions, assume `chunk.getSections()` contains every Y, or inspect vanilla light storage internals can retain vanilla representation limits inside their own code.

## World generation

Natural terrain remains in the generator's normal range. The sparse space is additional buildable volume; Endless does not generate terrain millions of blocks high by default.
