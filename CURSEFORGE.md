![](https://media.forgecdn.net/attachments/description/null/description_44f011ce-3daa-4641-85fa-ee4abac4d7dc.png)

![](https://media.forgecdn.net/attachments/description/null/description_7a3dcf6f-3695-46ef-9696-746d08598dc7.png)

Endless lets you build higher and dig deeper than ever before. In v0.5, you can configure your world's build range anywhere from Y=-8,000,000 through Y=7,999,999 without turning every chunk into a millions-of-block-tall section array.

## Features

* **Practical Infinite Build Height** — Choose any section-aligned build range inside Y=-8,000,000 to Y=7,999,999.
* **Sparse Vertical Storage** — Fresh worlds keep Minecraft's normal `[-64,320)` dense chunk core. Extended construction is stored in 512-block vertical pages that are allocated only where data exists.
* **All Dimensions Supported** — Works in the Overworld, Nether, End, and other normal dimensions.
* **Compatible with Existing Worlds** — v0.5 preserves the fail-closed migration safeguards from v0.4 so old extended sections are not silently discarded. Back up important worlds before upgrading.
* **Multi-version loaders** - Minecraft 1.20.1 is supported on Fabric/Forge, Minecraft 1.21.1 on Fabric/NeoForge, and Minecraft 26.1.2 on NeoForge.
* **Persistent High-Y Blocks** — Sparse pages use dedicated compressed storage outside vanilla Anvil section serialization, so blocks far above or below vanilla limits survive save and reload.
* **Blocks, Fluids and Block Entities** — Normal block access, fluids, block entities, scheduled ticks and block updates work throughout the configured range.
* **Heightmaps, Lighting and POIs** — Sparse blocks participate in supported height queries, block/sky lighting, and point-of-interest storage without widening vanilla section arrays.
* **Server-Authoritative Multiplayer** — Servers synchronize the configured logical range and dense layout before sparse world data is used. Extended worlds require Endless v0.5-compatible clients.
* **Waystones Compatibility** - Development/runtime compatibility coverage is included where matching Waystones artifacts are available; the 1.20.1 sparse placement path is explicitly exercised by the live matrix.
* **Camera-Following Rendering** — The client renders a 512-block vertical window around the camera instead of allocating render chunks for the entire logical build range.
* **Void Damage at the Boundary** — The below-world kill plane follows your configured minimum and triggers 64 blocks below it.

## Configuration

After launching the game once with Endless installed, a config file is created at `config/endless.json`. Open it with any text editor to customize the logical build range:

```json
{
  "buildHeight": {
    "minBuildHeight": -64,
    "maxBuildHeight": 320
  }
}
```

* **minBuildHeight** — Lowest legal Y-level. It is inclusive. Default: `-64`. Minimum supported value: `-8000000`.
* **maxBuildHeight** — Upper build boundary. It is exclusive, so `320` means Y=319 is the highest legal block. Default: `320`. Maximum supported value: `8000000`.
* Values are normalized to 16-block section boundaries.
* Restart the game or server after changing the file. In multiplayer, the server's configured range is authoritative.

### Example: Full Height

To unlock the complete v0.5 representation envelope:

```json
{
  "buildHeight": {
    "minBuildHeight": -8000000,
    "maxBuildHeight": 8000000
  }
}
```

This allows building from Y=-8,000,000 through Y=7,999,999.

### Example: Extended Range

For a large but easier-to-navigate range:

```json
{
  "buildHeight": {
    "minBuildHeight": -4096,
    "maxBuildHeight": 4096
  }
}
```

Because v0.5 uses sparse storage, simply widening the configured range no longer allocates a dense section array for every possible Y-level. Memory and storage grow primarily with the extended pages that actually contain data.

## Existing Worlds

Fresh v0.5 worlds always keep a vanilla-sized `[-64,320)` dense core internally, even when the configured logical range is millions of blocks tall.

Worlds upgraded from older Endless versions may retain a wider historical dense core, up to the legacy-safe `[-2032,2032)` envelope, when required to preserve old Anvil data. That internal compatibility range does not widen your configured build limit.

Played pre-v0.4 worlds are inspected before chunks load. If Endless cannot prove that an old layout can be migrated safely, startup fails closed instead of allowing Minecraft to silently discard sections. Back up important worlds before upgrading.

## Limitations

* **World generation** — Natural terrain still uses the generator's normal vertical range. Endless adds buildable space; it does not generate terrain millions of blocks high or deep by default.
* **Rendering distance vertically** — The client keeps a 512-block vertical render window around the camera. Far-away sparse pages remain saved and active server-side but are rendered when the camera approaches them.
* **Representation envelope** — v0.5 is practically unbounded, not mathematically infinite. The supported logical range is `[-8000000, 8000000)`.
* **Mod compatibility** — Mods using normal `Level`, `LevelChunk`, `BlockPos`, block entity, tick, POI, heightmap and brightness APIs can work through Endless' routing. Mods that directly pack high-Y positions with `BlockPos.asLong()`, assume `chunk.getSections()` contains every possible Y, or inspect vanilla light storage internals may retain vanilla limits in their own code.
* **Multiplayer clients** — Sparse v0.5 worlds require Endless v0.5-compatible clients; vanilla clients cannot join an extended-range Endless server.

### Suggestions & Bug Reports

Discord: [Join the community](https://discord.gg/4vD9WuT2As)

Github Issues: [Report issues](https://github.com/UpperMoon0/Endless/issues)
