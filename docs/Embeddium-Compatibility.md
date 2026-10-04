# Embeddium / Oculus adapter

Scope: **Minecraft 1.20.1 Forge, Embeddium 0.3.31, Oculus 1.8.0**. Embeddium is a compile-only dependency, never bundled. A Forge mixin plugin requires the `embeddium` mod ID; shared Sodium class names alone do not enable the adapter. Client-only mixins do not change dedicated-server rendering or other loader/version lines.

## Rendering contract

Endless keeps vanilla's dense section array bounded. Logical `Level.isOutsideBuildHeight` therefore cannot guard another mod's direct dense-array access. The adapter supplies sections to Embeddium's clone cache, world-slice origin check and section-registration emptiness check using a bounds-checked dense lookup or an existing sparse section. Render lookups never create sparse pages.

Each ready horizontal chunk has at most 32 vertical render sections. The main camera controls the window with eight-section hysteresis. Rebase removes departed nodes through Embeddium's native cancellation/disposal path, invalidates cached clones, creates entering nodes and clears stale render lists. Native upload filtering rejects disposed sections. Occlusion traversal uses the same bounds. Oculus shadow passes may rebuild visibility, but do not move the vertical window away from the main camera.

Palette and biome containers retain Embeddium's native cloning. Before its native chunk-map snapshot, sparse sections materialize missing block entities through the chunk's normal lazy-creation API and remove entries whose blocks were replaced by an arriving page. Sparse and dense-boundary lighting use independently owned DataLayers. Block light is solved once for the section plus a 14-block halo rather than once per target voxel; non-emissive palettes are skipped. Existing point lighting and batch lighting share the same attenuation and face-occlusion rules. Complete target-cell results may enter the bounded light cache; halo results are not cached as complete answers. Sparse page arrival and ordinary block updates use the existing LevelRenderer dirty path to invalidate Embeddium snapshots and rebuild sections.

Skylight queries each column in a 15-block halo once, shares those heights across the existing five-ray exposure rule, and skips ray reads for sections proven to be below every possible exposure path. This avoids repeating sparse heightmap queries for every voxel and ray step while retaining the point-solver results.

## Repeatable hidden framebuffer capture

Use an **isolated copy** of a world. The fixture creates small lighting/chest/glass fixtures at Y=-80, 320, 512 and ±1,000,000, changes time/weather in the copy, and teleports its integrated-server player. Do not point it at a world you want preserved unchanged.

1. Build `./gradlew :forge-1.20.1:build -PshaderPreview` with JDK 21 for Gradle.
2. Install the `*-shader-preview.jar` in a Forge 1.20.1 test instance with Architectury, Embeddium and the copied world's registry mods. Keep a single Endless jar installed. Install Oculus and Complementary for the shader run.
3. Copy the world under `.minecraft/saves/Endless Shader Preview`. Configure a logical range including the fixture heights.
4. Run `python tools/capture_shader_preview.py --instance PATH --output OUTPUT`. Add `--shaders` only when Oculus is configured with Complementary enabled. `--shots NAME,NAME` selects a focused rerun. `--vanilla` verifies a baseline with Embeddium and Oculus removed. Optional `--launcher PATH_TO_PRISM` launches the instance and waits for a validated manifest.
5. Launch that instance normally. The request file opts the development fixture in. It loads the copy, hides its GLFW window, disables focus pausing, waits for camera settlement and built target sections, and calls `Screenshot.takeScreenshot(mainRenderTarget)` at RenderTick END. It neither sends F2 nor activates a desktop window.
6. Inspect `capture-manifest.json`, `progress.json`, PNGs and `failure.txt`. Require all requested images and no failure. Review pixels too: a built section alone does not prove that its geometry appeared in the final frame.

The manifest records actual camera, dimensions, shader enablement/pack and capture method. The fixture checks the 32-section limit, disposed-node removal, sparse palettes, chest snapshots and light values. Its server preparation compares the batch solve to the existing per-point solver before render snapshots populate the client cache. The edit shot replaces glowstone with a lower-emission redstone torch, changes the marker, and removes the chest to check updated palette/light data and stale block-entity removal. The tower return and reload shots cover repeated rebasing and a renderer reload.

Ordinary builds omit this fixture and its requests. Captures and world copies are not release assets. The integration does not claim compatibility with Rubidium, arbitrary Sodium forks, dynamic-light mods, every shader pack, or other Minecraft/loader versions.

## Local validation, 2026-10-05

Forge 47.4.16 client, Embeddium 0.3.31, Oculus 1.8.0 and Complementary Reimagined r5.9.3, using a copied YouTube world with Create 6.0.8 and Architectury 9.2.14:

| Run | Result |
| --- | --- |
| Embeddium, Oculus absent | 12 captures, 1920x1080 |
| Embeddium + Oculus, Complementary active | 12 captures, 1920x1080; actual active pipeline verified |
| Embeddium + Oculus, shaders disabled | Million-height fixture and edit/removal: 2 captures |
| Vanilla renderer, Embeddium/Oculus absent | Upper tower and renderer reload: 2 captures |
| Normal Forge and Fabric 1.20.1 builds | Passed |
| Common and common-1.20.1 unit tests | 101 passed, one existing skipped test; three new window regressions |
| Normal Forge jar inspection | No preview fixture or bundled renderer; Create runtime refmap check passed |

The full runs include tower base/boundary/upper/crown, Y=-80/320/512 and ±1,000,000 fixtures, return travel, renderer reload, and palette/light edits. The focused shaders-disabled run additionally verifies chest removal from both cloned data and the client chunk map. Reviewed final-frame pixels include upper tower, crown and the positive million-height fixture. These are local integration results; CI and release/publication are separate. Dedicated-server behavior and moving Create contraptions were not exercised by this capture fixture.
