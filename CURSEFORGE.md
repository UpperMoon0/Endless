Endless lets you build higher and dig deeper than ever before. Tired of hitting the build limit? With Endless, you control how tall your world can be — up to Y=8192 and down to Y=-4096.

## Features

- **Extend Build Height** — Set your own minimum and maximum build heights. Build towering skyscrapers reaching thousands of blocks into the sky, or dig deeper than the vanilla limit allows.
- **All Dimensions Supported** — Works in the Overworld, Nether, and End simultaneously.
- **Compatible with Existing Worlds** — Apply expanded build limits to worlds you already have.
- **Works with Forge and Fabric** — Pick your loader, the mod works the same on both.

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

- **minBuildHeight** — Lowest Y-level you can place blocks (default: -64, minimum: -4096)
- **maxBuildHeight** — Highest Y-level you can place blocks (default: 320, maximum: 8192)

Change the values to whatever range you want, save the file, and restart the game.

### Example: Full Height

Want to unlock the full height range? Set both values to their extremes:

```json
{
  "buildHeight": {
    "minBuildHeight": -4096,
    "maxBuildHeight": 8192
  }
}
```

## Limitations

- **Lighting range**: Dynamic lighting updates (block light and sky light) are limited to y=-1024 to y=1024 to keep the game running smoothly. Blocks placed far outside this range may appear incorrectly lit. Natural light from the sky should still reach all heights.
- **Performance**: Expanding the build height increases memory usage. For most players, a range of y=-512 to y=1024 provides a great balance of freedom and performance.
