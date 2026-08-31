Endless lets you build higher and dig deeper than ever before. Tired of hitting the build limit? With Endless, you control how tall your world can be — up to Y=2047 and down to Y=-2048.

## Features

- **Extend Build Height** — Set your own minimum and maximum build heights. Build towering skyscrapers, or dig deeper than the vanilla limit allows.
- **All Dimensions Supported** — Works in the Overworld, Nether, and End simultaneously.
- **Compatible with Existing Worlds** — Apply expanded build limits to worlds you already have.
- **Works with Forge and Fabric** — Pick your loader, the mod works the same on both.
- **Waystones Compatible** — Place waystone blocks anywhere within your expanded build range.
- **Void Damage at Boundary** — Void damage triggers 64 blocks below your configured minimum, giving you safe space to build at the bottom of the world.
- **Camera-Following Render Window** — The client renders a 512-block-tall window around the camera instead of the entire world height.

## Why the range is capped at ±2048

Vanilla packs block positions into a 64-bit long with only 12 bits for Y, so any coordinate outside [-2048, 2048] silently wraps when a position is packed — in heightmaps, block-update packets, and block entity positions. Endless therefore clamps the configured range to the largest envelope the game can actually represent, rather than accepting values that would silently corrupt worlds.

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

- **minBuildHeight** — Lowest Y-level you can place blocks (default: -64, minimum: -2048, must be a multiple of 16)
- **maxBuildHeight** — Exclusive upper bound: the highest placeable block is `maxBuildHeight - 1` (default: 320, maximum: 2048, snapped up to a multiple of 16)

Change the values to whatever range you want, save the file, and restart the game. If the file is malformed, Endless falls back to defaults and preserves your file as `endless.json.broken`.

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

## Limitations

- **Rendering**: The client renders a vertical window of 32 sections (512 blocks) that follows the camera. Blocks outside the window are still placeable, saved, and fully functional — they simply are not rendered until you get closer vertically.
- **World generation**: Terrain generation only uses the vanilla height range. Extended build height gives you more *buildable* space, but natural terrain still generates between y=-64 and y=320.
- **Per-side config**: There is no server-authoritative config sync — make sure a server and its clients use the same build heights.
