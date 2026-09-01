# Endless

Endless is a Minecraft mod that extends the build height limits of the game, allowing you to configure custom build ranges within Y=-2032 (lowest placeable block) and Y=2031 (highest placeable block).

CurseForge: https://www.curseforge.com/minecraft/mc-mods/nstut-endless

![Endless](assets/icon.png)

## Why the range is capped at ±2032

Vanilla packs block positions into a 64-bit long with only 12 bits for Y, giving a raw envelope of [-2048, 2048). Vanilla deliberately reserves one 16-block guard section at each edge of that envelope (its `DimensionType` spans [-2032, 2032)): engine operations such as `BlockPos.offset` and light propagation step to neighboring packed positions, and without the guard band the block above the ceiling (or below the floor) would silently wrap to the opposite end of the world. Endless uses the same guard-banded range, so the highest placeable block is Y=2031 and the lowest is Y=-2032.

Every packed `BlockPos` long — heightmaps, block entity positions, block-update packets, entity tracking — silently wraps outside that envelope. Until position serialization itself is replaced (a much deeper engine change), allowing larger configured heights would produce silent, impossible-to-debug world corruption, so the mod clamps the config to the safe envelope.

Minecraft 1.20.1 chunk files independently impose another hard legacy boundary: each section stores its absolute section Y as a signed byte. That represents raw section coordinates -128..127, or block coverage [-2048, 2048). v0.4 intentionally does not reinterpret or wrap those coordinates during migration.

## Features

- **Configurable Build Height** — Choose any 16-aligned minimum and maximum within the ±2032 envelope (default: vanilla -64 to 320)
- **All Dimensions** — Works simultaneously in Overworld, Nether, and End
- **Cross-Platform** — Compatible with both Forge and Fabric
- **Waystones Compatible** — Place waystones anywhere within your expanded build range
- **Camera-Following Render Window** — The client renders a vertical window around the camera instead of allocating render chunks for the entire height
- **World-Persistent Ranges** — The build range is stored in the world save; once persisted it only ever widens, so saved sections do not become unreachable when the config changes
- **Fail-Closed Legacy Migration** — Played pre-v0.4 worlds are classified before any chunk is loaded; unsafe or ambiguous histories are refused instead of silently losing sections
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

If the config file is malformed, Endless uses defaults in memory and attempts to preserve the original as `endless.json.broken`. If the backup cannot be created, the original is never overwritten automatically.

### World persistence and pre-v0.4 migration

The config file expresses what you *want*; each v0.4+ world records the range it actually uses in its save (`data/endless_build_heights.dat`). On load — before any chunk is deserialized — the effective range is the union of the world's stored range and the config:

- Widening the config expands the world.
- Narrowing the config cannot shrink the persisted world range, because vanilla sizes the chunk section array from the current world height and skips saved section Y values that fall outside it.
- An existing but unreadable/invalid persisted range is treated as a startup error rather than silently falling back to the current config.

Played worlds created before v0.4 have no per-world persisted range. Their old `endless.json` was global, so the current raw config is only a **migration candidate**, not proof of the range with which this particular world was last saved. v0.4 therefore performs a one-time fail-closed migration gate **before any `ServerLevel` or chunk is loaded** and keeps the raw config unchanged on disk until that gate succeeds.

For every played pre-v0.4 world:

- The raw legacy config is first normalized without applying the new guard clamp. Ranges outside the signed-byte section-Y envelope `[-2048, 2048)`, spans greater than 4096 blocks, missing/untrusted config, or malformed/unreadable migration inputs are refused.
- Every legacy dimension region file is then inspected with vanilla `RegionFile`/NBT readers. Saved section payloads or block entities outside the candidate range prove that the global config no longer describes this world's historical layout, so startup fails closed instead of loading a narrower section array.
- Saved heightmap packing is checked against the raw legacy span. For example, a current `[-64, 320)` config expects 37 longs per 1.20.1 heightmap; a saved 64-long heightmap is evidence of a 4096-block historical span and causes migration to stop rather than guess.
- The only sections v0.4 may intentionally discard are raw guard sections `Y=-128` and/or `Y=127` when migrating a legacy range that reached `-2048`/`2048`. Those sections are outside the safe v0.4 envelope and are discarded only when their block palette is provably air-only and no block entity occupies them. Meaningful or malformed edge data fails closed.

Only after all saved-world evidence agrees with the candidate may Endless apply the effective range, normalize the config, and create the new world-persistent range. This prevents a changed global config from making old high/low sections unreachable and then permanently erasing them on resave.

In multiplayer the world's range is what the server syncs to clients. Every **remote login connection** starts from the vanilla baseline when `ClientHandshakePacketListenerImpl` is constructed; this works for both online- and offline-mode servers. Fabric/Forge login sync may then overwrite that baseline before `ClientboundLoginPacket` constructs the client world. Integrated-server memory connections keep the shared server-authoritative range. Clients without the mod can join servers whose range is vanilla; on servers with an extended range they are disconnected with a clear message instead of loading mismatched chunk data.

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