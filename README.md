# Endless

Endless is a Minecraft mod that extends the build height limits of the game, allowing you to configure custom build ranges within Y=-2032 (lowest placeable block) and Y=2031 (highest placeable block).

CurseForge: https://www.curseforge.com/minecraft/mc-mods/nstut-endless

![Endless](assets/icon.png)

## Why the range is capped at ±2032

Vanilla packs block positions into a 64-bit long with only 12 bits for Y, giving a raw envelope of [-2048, 2048). Vanilla deliberately reserves one 16-block guard section at each edge of that envelope (its `DimensionType` spans [-2032, 2032)): engine operations such as `BlockPos.offset` and light propagation step to neighboring packed positions, and without the guard band the block above the ceiling (or below the floor) would silently wrap to the opposite end of the world. Endless uses the same guard-banded range, so the highest placeable block is Y=2031 and the lowest is Y=-2032.

Every packed `BlockPos` long — heightmaps, block entity positions, block-update packets, entity tracking — silently wraps outside that envelope. Until position serialization itself is replaced (a much deeper engine change), allowing larger configured heights would produce silent, impossible-to-debug world corruption, so the mod clamps the config to the safe envelope.

## Features

- **Configurable Build Height** — Choose any 16-aligned minimum and maximum within the ±2032 envelope (default: vanilla -64 to 320)
- **All Dimensions** — Works simultaneously in Overworld, Nether, and End
- **Cross-Platform** — Compatible with both Forge and Fabric
- **Waystones Compatible** — Place waystones anywhere within your expanded build range
- **Camera-Following Render Window** — The client renders a vertical window around the camera instead of allocating render chunks for the entire height
- **World-Persistent Ranges** — The build range is stored in the world save; it only ever widens, so saved chunks can never become unreachable when the config changes
- **Server-Authoritative Sync** — Servers send their build range to clients during login, before any chunk data; clients without the mod are rejected when the server's range is extended

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

- `minBuildHeight`: Lowest Y-level for block placement (default: -64, minimum: -2032, must be a multiple of 16)
- `maxBuildHeight`: Exclusive upper bound — the highest placeable block is `maxBuildHeight - 1` (default: 320, maximum: 2032, snapped up to a multiple of 16)

If the config file is malformed, the mod falls back to defaults and preserves your file as `endless.json.broken`.

### World persistence

The config file expresses what you *want*; each world records the range it was actually built with in its save (`data/endless_build_heights.dat`). On load — before any chunk is deserialized — the effective range is the *union* of the world's stored range and the config:

- Widening the config expands the world (existing chunks keep working).
- Narrowing the config is rejected with a log warning, because shrinking the section array would silently drop saved chunks above or below the new bounds.

In multiplayer the world's range is what the server syncs to clients. Clients without the mod can join servers whose range is vanilla; on servers with an extended range they are disconnected with a clear message instead of loading mismatched chunk data.

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

Run unit/build tests:

```bash
./gradlew test
./gradlew build
```

Launch a development client:

```bash
./gradlew runFabricClient
./gradlew runForgeClient
```

Launch a development server:

```bash
./gradlew runFabricServer
./gradlew runForgeServer
```

The fully qualified Loom tasks are also available:

```bash
./gradlew :fabric:runClient
./gradlew :forge:runClient
./gradlew :fabric:runServer
./gradlew :forge:runServer
```

Do not use bare `runClient` or `runServer`. Endless is a multi-project Architectury workspace, so an unqualified Gradle task selector can match run tasks from multiple loader projects.

## License

All Rights Reserved

## Author

- NsTut
