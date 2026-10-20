# Tall views and cinematic rendering

Endless defaults to a camera-following 32-section (512-block) render window.
To keep a tall structure visible in a single frame, add this to `config/endless.json`
on **both the client and server**, preserving the existing `buildHeight` settings:

```json
"rendering": {
  "verticalRenderSections": 256
}
```

Restart both processes after editing. The server must use the same or a larger
budget than the client; this local configuration is not negotiated over the network.
256 sections cover 4,096 vertical blocks. Values are clamped to 32..512 sections
(512..8,192 blocks); the logical build envelope never sizes the render grid.

The window centers around the main camera, with eight sections of rebase
hysteresis. Near a logical boundary it shifts to remain inside the world.
A 4,096-block window can include a ground terminal at Y=82 and a station at
Y=2,190 from a camera around Y=200. Field of view, horizontal distance, projection,
fog and occlusion still determine what appears in the final image.

The server expands its sparse-page scan and block-entity update recipients to
cover this window, including page seams and hysteresis. Existing per-player queue,
byte/time, scan and fair-send budgets still apply, so larger views take longer to
populate. Renderer adapters retain horizontal distance/frustum culling and let the
bounded section graph govern vertical distance when the budget exceeds the default.
The default budget keeps the renderer's original distance predicate.

Create's controlled contraptions can spawn before a distant sparse controller
page arrives. The client defers its controller-loaded check until that page has
been received; an empty received page still counts as known, so a genuinely
missing controller keeps Create's normal removal behavior. Horizontal chunk
unloads and reconnects clear the receipt state.

Larger budgets increase node, mesh, snapshot and page-delivery work for every
tracked horizontal column. Start at 128 or 256 sections and allow initial meshes
to finish before recording. Increasing horizontal distance at the same time can
multiply the cost substantially. Vanilla's dense render grid is especially costly.

Complementary's TAA/DH blend normally fades native terrain using both horizontal
and vertical distance. In expanded mode, Endless changes the recognized
`DH_BLENDING` expression to horizontal distance during Iris/Oculus preprocessing.
This preserves the ground-level seam, shader `far` uniform and DH near boundary;
raising the shared `far` value would create a gap between native terrain and LODs.
Unrecognized shader expressions and the default budget are left unchanged.
Shader packs still control their own atmospheric/border fog; use their settings
when haze hides a structure that the renderer has already made visible.

Native block and skylight snapshots each use a bounded 4,096-section cache, with
at most 16 MiB of combined nibble-light payload. Each clone receives its own copy.
Lighting edits, changed page contents and column unloads invalidate cached
results. Confirming previously unknown air still records the page receipt, but
retains solved lighting because the scene did not change. This lets tall moving
views, neighboring mesh snapshots and shadow/main passes reuse their working set.

Distant Horizons provides its own distant terrain; this option expands native
Minecraft geometry and does not claim to extend DH's LOD height representation.
Keep DH and the selected shader enabled during final framing and export checks.
A successful build alone does not establish runtime shader or LOD compatibility.

Sparse POI sections are indexed by their owning horizontal column. Listing,
saving and unloading a column visits only its admitted sparse sections; it never
walks vanilla's global POI section map. This avoids watchdog stalls when long
camera or player routes unload many ordinary terrain chunks. Dirty sidecars
are still saved before their indexed sections are removed, and reloading rebuilds
the index from the sidecar.
