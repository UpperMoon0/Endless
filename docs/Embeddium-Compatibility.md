# Embeddium / Oculus adapter

Scope: **Minecraft 1.20.1 Forge, Embeddium 0.3.31, Oculus 1.8.0**. Embeddium is a compile-only dependency, never bundled. A Forge mixin plugin requires the `embeddium` mod ID; shared Sodium class names alone do not enable the adapter. Client-only mixins do not change dedicated-server rendering or other loader/version lines.

## Rendering contract

Endless keeps vanilla's dense section array bounded. Logical `Level.isOutsideBuildHeight` therefore cannot guard another mod's direct dense-array access. The adapter supplies sections to Embeddium's clone cache, world-slice origin check and section-registration emptiness check using a bounds-checked dense lookup or an existing sparse section. Render lookups never create sparse pages.

Each ready horizontal chunk has at most 32 vertical render sections. The main camera controls the window with eight-section hysteresis. Rebase removes departed nodes through Embeddium's native cancellation/disposal path, invalidates cached clones, creates entering nodes and clears stale render lists. Native upload filtering rejects disposed sections. Occlusion traversal uses the same bounds. Oculus shadow passes may rebuild visibility, but do not move the vertical window away from the main camera.

Palette and biome containers retain Embeddium's native cloning. Before its native chunk-map snapshot, sparse sections materialize missing block entities through the chunk's normal lazy-creation API and remove entries whose blocks were replaced by an arriving page. Sparse and dense-boundary lighting use independently owned DataLayers. Block light is solved once for the section plus a 14-block halo rather than once per target voxel; non-emissive palettes are skipped. Existing point lighting and batch lighting share the same attenuation and face-occlusion rules. Complete target-cell results may enter the bounded light cache; halo results are not cached as complete answers. Sparse page arrival and ordinary block updates use the existing LevelRenderer dirty path to invalidate Embeddium snapshots and rebuild sections.

Skylight queries each column in a 15-block halo once, shares those heights across the existing five-ray exposure rule, and skips ray reads for sections proven to be below every possible exposure path. This avoids repeating sparse heightmap queries for every voxel and ray step while retaining the point-solver results.

A page arriving outside the render window can still change sky exposure below it. Page application invalidates snapshots and schedules native rebuilds in the ready 3-by-3 horizontal column halo across the current 32-section window, including removal of a distant roof. Dimensions without skylight skip this extra work.

Both the initial meshing task and subsequent transparency sorting subtract the section origin in double precision before converting to float. Subsequent tasks retain the manager's immutable double camera snapshot; they never read a moving camera on the worker thread. Embeddium's replacement block-entity renderer also uses the same full-position destruction keys as Endless's LevelRenderer, including cleanup and simultaneous vanilla packed-Y aliases.

## Repeatable hidden framebuffer capture

Use an **isolated copy** of a world. The fixture creates small lighting/chest/glass fixtures at Y=-80, 320, 512 and ±1,000,000, changes time/weather in the copy, and teleports its integrated-server player. Do not point it at a world you want preserved unchanged.

1. Build `./gradlew :forge-1.20.1:build -PshaderPreview` with JDK 21 for Gradle.
2. Install the `*-shader-preview.jar` in a Forge 1.20.1 test instance with Architectury, Embeddium and the copied world's registry mods. Keep a single Endless jar installed. Install Oculus and Complementary for the shader run.
3. Copy the world under `.minecraft/saves/Endless Shader Preview`. Configure a logical range including the fixture heights.
4. Run `python tools/capture_shader_preview.py --instance PATH --output OUTPUT`. Add `--shaders` only when Oculus is configured with Complementary enabled. `--shots NAME,NAME` selects a focused rerun. `--vanilla` verifies a baseline with Embeddium and Oculus removed. Optional `--launcher PATH_TO_PRISM` launches the instance and waits for a validated manifest.
5. Launch that instance normally. The request file opts the development fixture in. It loads the copy, hides its GLFW window, disables focus pausing, waits for camera settlement and built target sections, and calls `Screenshot.takeScreenshot(mainRenderTarget)` at RenderTick END. It neither sends F2 nor activates a desktop window.
6. Inspect `capture-manifest.json`, `progress.json`, PNGs and `failure.txt`. Require all requested images and no failure. Review pixels too: a built section alone does not prove that its geometry appeared in the final frame.

The manifest records actual camera, dimensions, shader enablement/pack and capture method. The fixture checks the 32-section limit, disposed-node removal, sparse palettes, chest snapshots and light values. Its server preparation compares the batch solve to the existing per-point solver before render snapshots populate the client cache. The edit shot replaces glowstone with a lower-emission redstone torch, changes the marker, and removes the chest to check updated palette/light data and stale block-entity removal. The tower return and reload shots cover repeated rebasing and a renderer reload.

Each unedited Embeddium fixture additionally checks native transparent index order on both sides of a fractional camera boundary at Y=0, ±1,000,000 and the ±8,000,000 envelope. The re-sort task comes from the transformed native manager; initial sorting invokes the transformed native meshing hook and the real native index-buffer sorter. The original absolute-float algorithm is a failing negative control. Crack checks call Embeddium's transformed block-entity lookup with two simultaneous positions separated by 4,096 Y, then check removal. Positive-height fixtures apply and remove a 48-by-48 roof page group 1,024 blocks above the fixture through the normal client-page update path, requiring fresh cached sky snapshots and restored light. These client-only roof snapshots are restored before capture. Successful manifests record these checks under `compatibilityRegressions`.

`python tools/check_embeddium_mixin_refmap.py PATH_TO_NORMAL_FORGE_JAR` checks all nine optional client hooks, their Minecraft runtime selectors, and exclusion of renderer/preview classes from ordinary jars. The Minecraft 1.20.1 build job runs this packaging gate; framebuffer checks remain local integration tests.

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

## Follow-up compatibility audit, 2026-10-06

The follow-up found and fixed three remaining adapter mismatches: absolute-float transparency sorting, truncated block-entity destruction lookups, and cached visible skylight after distant page updates. The audit checked upstream source and the pinned 0.3.31 artifact; native task creation and transformed hooks were exercised in the release-loader client.

| Run | Result |
| --- | --- |
| Embeddium, Oculus absent | All 12 captures passed; new native sorting, crack and distant-sky-page checks passed |
| Embeddium + Oculus, Complementary active | All 12 captures passed; new native regressions passed with the active pipeline |
| Embeddium + Oculus, shaders disabled | Million-height fixture and edit/removal: 2 captures passed; new native regressions passed |
| Vanilla renderer, Embeddium/Oculus absent | Upper tower and renderer reload: 2 captures passed |
| Normal Forge/Fabric 1.20.1 builds; shared 1.21.1 compile | Passed |
| Common/common-1.20.1 unit tests | 101 passed, one existing skip |
| Python harness and metadata checks | 49 tests passed; five-target metadata passed |
| Packaged Forge Create/Embeddium selectors | Passed; normal jar excludes preview and renderer classes |

Positive million-height frames with and without Complementary, and the upper tower without shaders, were visually reviewed. Native transparency checks cover Y=0, ±1,000,000 and the ±8,000,000 envelope; the captured terrain fixtures remain at the five stated heights. The old absolute-float sorter fails the native index-order negative control. Distant-sky checks require both roof arrival and removal to replace the cached visible light data, while the section-count assertions retain the 32-section bound. This does not expand the supported renderer/version scope or certify moving contraptions, arbitrary renderer addons or shader packs.
