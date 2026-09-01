Endless lets you build higher and dig deeper than ever before. Tired of hitting the build limit? With Endless, you control how tall your world can be — up to Y=2031 and down to Y=-2032.

## Features

- **Extend Build Height** — Set your own minimum and maximum build heights. Build towering skyscrapers, or dig deeper than the vanilla limit allows.
- **All Dimensions Supported** — Works in the Overworld, Nether, and End simultaneously.
- **Compatible with Existing Worlds** — Expanded limits can be applied safely to existing worlds; pre-v0.4 worlds are migration-checked before any chunk loads.
- **Works with Forge and Fabric** — Pick your loader, the mod works the same on both.
- **Waystones Compatible** — Place waystone blocks anywhere within your expanded build range.
- **World-Persistent Ranges** — Each v0.4+ world records its build range in the save; ranges only ever widen after persistence so saved sections cannot become unreachable when the config changes.
- **Fail-Closed Legacy Migration** — Old worlds with ambiguous or unsafe vertical history are refused instead of silently discarding chunk sections.
- **Server-Authoritative Sync** — Multiplayer servers send their build range to every client during login, before any chunk data is sent.
- **Camera-Following Render Window** — The client renders a 512-block-tall window around the camera instead of the entire world height.

## Why the range is capped at ±2032

Vanilla packs block positions into a 64-bit long with only 12 bits for Y. Vanilla deliberately reserves a 16-block guard section at each edge of that envelope (its DimensionType spans [-2032, 2032)): operations like placing a block or propagating light next to the top or bottom block step into a neighboring packed position, and without the guard band that neighbor would silently wrap to the opposite end of the world. Endless therefore clamps the configured range to the same guard-banded envelope, rather than accepting values that would silently corrupt worlds.

Minecraft 1.20.1 chunk files also store each section's absolute section Y as a signed byte, giving raw section coordinates -128..127 (block coverage [-2048, 2048)). v0.4 does not reinterpret or wrap those legacy coordinates.

## Configuration

After launching the game once with Endless installed, a config file is created at `config/endless.json`. Open it with any text editor to customize:

```json
{
  "buildHeight": {
    "minBuildHeight": -64,
    "maxBuildHeight": 320
  }
}
```

- **minBuildHeight** — Lowest Y-level you can place blocks (default: -64, minimum: -2032, must be a multiple of 16)
- **maxBuildHeight** — Exclusive upper bound: the highest placeable block is `maxBuildHeight - 1` (default: 320, maximum: 2032, snapped up to a multiple of 16)

Change the values to whatever range you want, save the file, and restart the game. If the file is malformed, Endless uses defaults in memory and attempts to preserve the original as `endless.json.broken`; if that backup fails, the original is not overwritten automatically.

### Example: Tall World

```json
{
  "buildHeight": {
    "minBuildHeight": -1024,
    "maxBuildHeight": 1024
  }
}
```

This gives 2048 blocks of height — 128 sections per chunk, roughly 5x vanilla memory for section arrays.

### Recommended Balanced Range

For a good balance of freedom and performance:

```json
{
  "buildHeight": {
    "minBuildHeight": -512,
    "maxBuildHeight": 1024
  }
}
```

### World persistence and old-world safety

Each v0.4+ world records the build range it uses. Widening the config expands that world; narrowing the config is rejected so saved sections above or below a smaller range are never silently dropped.

Played pre-v0.4 worlds have no persisted range, so their first v0.4 startup is classified before any chunk is loaded:

- A legacy range already inside `[-2032, 2032)` migrates automatically.
- A raw-edge range such as `[-2048, 2048)` is scanned for meaningful data in legacy section `Y=-128` / `Y=127`. Air-only edge sections may be clamped away; non-air blocks, block entities, malformed edge data, or unreadable region files stop startup instead of discarding data.
- Legacy ranges outside the signed-byte section-Y envelope or wider than 4096 blocks are refused and require an explicit conversion path.
- Endless preserves the raw legacy config until this decision is complete, then writes the new persisted world range only after migration succeeds.

Back up important old worlds before upgrading. If Endless refuses a legacy world, do not force a narrower range and resave it; convert or recover the old layout first.

## Limitations

- **Rendering**: The client renders a vertical window of 32 sections (512 blocks) that follows the camera. Blocks outside the window are still placeable, saved, and fully functional — they simply are not rendered until you get closer vertically.
- **World generation**: Terrain generation only uses the vanilla height range. Extended build height gives you more *buildable* space, but natural terrain still generates between y=-64 and y=320.
- **Vanilla clients**: A client without Endless cannot correctly read chunk data from a server whose build range is extended (chunk sections carry no Y coordinates on the wire). Such clients are disconnected with a clear message on those servers; they can still join servers whose range is vanilla.
- **Maximum-height memory use**: 254 real chunk sections is substantially heavier than vanilla. v0.4 prevents the legacy data-loss path described above, but fresh-world maximum-height memory/GC profiling remains a separate performance validation item.
