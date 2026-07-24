Endless lets you build higher and dig deeper than ever before. Tired of hitting the build limit? With Endless, you control how tall your world can be — up to Y=2,097,152 and down to Y=-2,097,152.

## Features

- **Extend Build Height** — Set your own minimum and maximum build heights. Build towering skyscrapers reaching thousands of blocks into the sky, or dig deeper than the vanilla limit allows.
- **All Dimensions Supported** — Works in the Overworld, Nether, and End simultaneously.
- **Compatible with Existing Worlds** — Apply expanded build limits to worlds you already have.
- **Works with Forge and Fabric** — Pick your loader, the mod works the same on both.
- **Waystones Compatible** — Place waystone blocks anywhere within your expanded build range.
- **Void Damage at Boundary** — Void damage triggers 64 blocks below your configured minimum, giving you safe space to build at the bottom of the world.

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

- **minBuildHeight** — Lowest Y-level you can place blocks (default: -64, minimum: -2,097,152)
- **maxBuildHeight** — Highest Y-level you can place blocks (default: 320, maximum: 2,097,152)
- **Max effective range**: ~1,048,576 blocks (65,536 sections). The config accepts wider values but the section array caps at 65,536 to stay within 512KB per chunk. Beyond that, system memory limits apply.

Change the values to whatever range you want, save the file, and restart the game.

### Example: Full Height

Want to unlock the full height range? Set both values to their extremes:

```json
{
  "buildHeight": {
    "minBuildHeight": -2097152,
    "maxBuildHeight": 2097152
  }
}
```

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

- **Rendering**: Blocks outside the vanilla render range (y=-384 to 384) are not visible. This prevents GPU buffer exhaustion at extreme heights. Blocks are still placeable, saved, and fully functional everywhere in your configured range — they just won't render beyond the camera's practical view limits.
- **World generation**: Terrain generation only uses the vanilla height range. Extended build height gives you more *buildable* space, but natural terrain still generates between y=-64 and y=320.
- **Block positions**: Y values beyond approximately ±2 million may not serialize correctly due to BlockPos bit limitations. Stay within the configurable range.
