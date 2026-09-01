Endless v0.5 adds sparse practical infinite build height for Minecraft 1.20.1: build from Y=-8,000,000 through Y=7,999,999 without allocating millions of empty chunk sections.

## What changed in v0.5

- **Sparse Vertical Pages** — Extended space is stored in 512-block pages and allocated only where blocks exist.
- **Dedicated High-Y Persistence** — Sparse pages use compressed NBT outside vanilla Anvil section serialization, avoiding signed-byte section-Y truncation.
- **Extended Position Protocol** — High-Y block positions use an Endless client/server encoding while normal positions keep vanilla's packed format.
- **Forge + Fabric** — The same sparse engine and login/page protocol are implemented on both loaders.
- **High-Y Blocks, Fluids and Block Entities** — Normal Level access and block lifecycle callbacks work outside the dense core.
- **Sparse Heightmaps** — World-surface, ocean-floor and motion-blocking height queries include high-Y pages.
- **Page-Aware Lighting** — Block light propagates through sparse pages without vanilla packed-Y wrapping; sky exposure accounts for dense and sparse column tops.
- **POI/Tick Compatibility** — The logical range deliberately remains inside vanilla's signed `SectionPos` Y envelope, preserving section-keyed POI and tick infrastructure.
- **Camera-Following Rendering** — The client renders a 512-block vertical window around the camera instead of allocating render chunks for the whole logical range.
- **Bounded Runtime Memory** — Sparse columns flush and unload with their horizontal chunks.
- **Old-World Safety Preserved** — v0.4's fail-closed dense-world migration gate remains in place.

## Dense core vs sparse range

`config/endless.json` still configures the dense compatibility core, which is guard-bounded to `[-2032, 2032)`. v0.5 does **not** make that dense array millions of blocks tall. Instead, everything outside the dense core uses the sparse page engine up to the fixed logical range `[-8000000, 8000000)`.

This separation keeps normal Minecraft chunk arrays, legacy Anvil data and migration safety intact while making large vertical construction practical.

## Multiplayer

Sparse v0.5 worlds require Endless v0.5-compatible clients. The server negotiates the logical-height protocol during login and sends only sparse pages near each player's current vertical window.

## Existing worlds

Played pre-v0.4 worlds are still inspected before chunks load. Endless refuses ambiguous or unsafe historical layouts instead of letting vanilla silently discard sections. Back up important worlds before upgrading.

## Compatibility notes

Mods using normal Level/LevelChunk/BlockPos, block entity, POI, tick, heightmap and brightness APIs can work through Endless' routing. Mods that directly call `BlockPos.asLong()` on high-Y positions, assume `chunk.getSections()` contains every Y, or inspect vanilla light storage internals can retain vanilla representation limits inside their own code.

## World generation

Natural terrain remains in the generator's normal range. The sparse space is additional buildable volume; Endless does not generate terrain millions of blocks high by default.
