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

Larger budgets increase node, mesh, snapshot and page-delivery work for every
tracked horizontal column. Start at 128 or 256 sections and allow initial meshes
to finish before recording. Increasing horizontal distance at the same time can
multiply the cost substantially. Vanilla's dense render grid is especially costly.

Distant Horizons provides its own distant terrain; this option expands native
Minecraft geometry and does not claim to extend DH's LOD height representation.
Keep DH and the selected shader enabled during final framing and export checks.
A successful build alone does not establish runtime shader or LOD compatibility.
