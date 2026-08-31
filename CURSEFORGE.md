Endless lets you build higher and dig deeper than ever before. Tired of hitting the build limit? With Endless, you control how tall your world can be — up to Y=2031 and down to Y=-2032.

## Features

- **Extend Build Height** — Set your own minimum and maximum build heights. Build towering skyscrapers, or dig deeper than the vanilla limit allows.
- **All Dimensions Supported** — Works in the Overworld, Nether, and End simultaneously.
- **Compatible with Existing Worlds** — Apply expanded build limits to worlds you already have.
- **Works with Forge and Fabric** — Pick your loader, the mod works the same on both.
- **Waystones Compatible** — Place waystone blocks anywhere within your expanded build range.
- **World-Persistent Ranges** — Each world records its build range in the save; ranges only ever widen, so your builds can never become unreachable when the config changes.
- **Server-Authoritative Sync** — Multiplayer servers send their build range to every client during login, before any chunk data is sent.
- **Camera-Following Render Window** — The client renders a 512-block-tall window around the camera instead of the entire world height.

## Why the range is capped at ±2032

Vanilla packs block positions into a 64-bit long with only 12 bits for Y. Vanilla deliberately reserves a 16-block guard section at each edge of that envelope (its DimensionType spans [-2032, 2032)): operations like placing a block or propagating light next to the top or bottom block step into a neighboring packed position, and without the guard band that neighbor would silently wrap to the opposite end of the world. Endless therefore clamps the configured range to the same guard-banded envelope, rather than accepting values that would silently corrupt worlds.

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

### World persistence

Each world records the build range it was created with. Widening the config expands that world; narrowing the config is rejected (with a log message) so saved chunks above or below a smaller range are never silently dropped.

## Limitations

- **Rendering**: The client renders a vertical window of 32 sections (512 blocks) that follows the camera. Blocks outside the window are still placeable, saved, and fully functional — they simply are not rendered until you get closer vertically.
- **World generation**: Terrain generation only uses the vanilla height range. Extended build height gives you more *buildable* space, but natural terrain still generates between y=-64 and y=320.
- **Vanilla clients**: A client without Endless cannot correctly join a server whose build range is extended (chunk sections carry no Y coordinates on the wire). Keep vanilla clients on vanilla-range servers.
