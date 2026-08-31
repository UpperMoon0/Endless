# Endless

Endless is a Minecraft mod that extends the build height limits of the game, allowing you to configure custom build ranges within Y=-2048 (inclusive) and Y=2048 (exclusive, so Y=2047 is the highest placeable block).

CurseForge: https://www.curseforge.com/minecraft/mc-mods/nstut-endless

![Endless](assets/icon.png)

## Why the range is capped at ±2048

Vanilla packs block positions into a 64-bit long with only 12 bits for Y, giving a usable range of [-2048, 2048]. Every packed `BlockPos` long — heightmaps, block entity positions, block-update packets, entity tracking — silently wraps outside that envelope. Until position serialization itself is replaced (a much deeper engine change), allowing larger configured heights would produce silent, impossible-to-debug world corruption, so the mod clamps the config to the safe envelope.

## Features

- **Configurable Build Height** — Choose any 16-aligned minimum and maximum within the ±2048 envelope (default: vanilla -64 to 320)
- **All Dimensions** — Works simultaneously in Overworld, Nether, and End
- **Cross-Platform** — Compatible with both Forge and Fabric
- **Waystones Compatible** — Place waystones anywhere within your expanded build range
- **Camera-Following Render Window** — The client renders a vertical window around the camera instead of allocating render chunks for the entire height

> **Note:** Extending build height increases memory usage. Each chunk allocates 16 sections per 256 blocks of configured height (vanilla allocates 24 sections for its 384 blocks).

## Configuration

After launching Minecraft with the mod for the first time, a configuration file is generated at `config/endless.json`:

```json
{
  "buildHeight": {
    "minBuildHeight": -64,
    "maxBuildHeight": 320
  }
}
```

Options:

- `minBuildHeight`: Lowest Y-level for block placement (default: -64, minimum: -2048, must be a multiple of 16)
- `maxBuildHeight`: Exclusive upper bound — the highest placeable block is `maxBuildHeight - 1` (default: 320, maximum: 2048, snapped up to a multiple of 16)

If the config file is malformed, the mod falls back to defaults and preserves your file as `endless.json.broken`.

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

## Development

### Project Structure

- `common/` — Shared code for all mod loaders
- `forge/` — Forge-specific implementation
- `fabric/` — Fabric-specific implementation

### Building

```bash
git clone https://github.com/UpperMoon0/Endless.git
cd Endless
./gradlew build
```

Output JARs in `forge/build/libs/` and `fabric/build/libs/`.

### Testing

```bash
./gradlew test          # Unit tests
./gradlew runClient     # Launch a client
./gradlew runServer     # Launch dedicated server
```

## License

All Rights Reserved

## Author

- NsTut
