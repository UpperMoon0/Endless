# Vertical level of detail

The detailed terrain renderer and the distant vertical view have separate budgets.
`rendering.verticalRenderSections` controls ordinary Minecraft meshes;
`rendering.verticalLodSections` controls the total vertical span of simplified
static block geometry. Values are section counts: one section is 16 blocks.

```json
"rendering": {
  "verticalRenderSections": 32,
  "verticalLodSections": 256
}
```

These defaults keep a 512-block detailed window and a 4,096-block distant
window. Set `verticalLodSections` to `0` to disable proxies. Both nonzero budgets
remain bounded to 32–512 sections. A proxy budget no larger than the detailed
budget does no additional rendering. Restart the client and server after editing;
the server must supply at least the client's total vertical span. These local
settings are not negotiated over the network. Logical world height never sizes
the render grid. Worlds that fit inside the detailed budget bypass the LOD pass.

## Geometry and lifecycle

The native renderer retains nearby block models, textures, lighting, entities,
block entities and Create rendering. Distant proxies approximate static blocks
with material colors, directional face shading and coarse ambient/emissive
lighting. They do not render distant moving contraptions, block-entity animations,
individual model details or texture transparency. The post-world color pass also
does not reproduce shader-pack material effects or correct blending through nearby
translucent surfaces. Increase the detailed budget
when a cinematic shot requires those features farther away.

Horizontal cells use four-block detail, dropping to eight blocks beyond 2,048
vertical blocks. Every source block is inspected; a thin cable cannot disappear
between samples. Occupied bounds are retained within cells, vertical air gaps are
preserved, and matching runs/rectangles are merged. Horizontal holes smaller than
the cell size can be approximated. Coordinates stay section-local; full signed
section positions select storage, including negative and extreme heights.

The active renderer publishes its exact native window, including rebase
hysteresis. That window takes precedence over proxies. The horizontal block
radius and five non-far frustum planes still cull distant sections. Proxy vertex
positions extend their own far-plane clipping without changing Minecraft's shared
`far` uniform or Distant Horizons' native/LOD seam.

Inventory visits at most 16 horizontal columns every 50 ms. Geometry builds
process at most two sections per frame with a soft 2 ms target; an indivisible
section can exceed the target. Inventory uses admitted sparse sections and
bounded dense arrays, never disk reads or a logical-height scan. Sparse delivery
retains its existing fair-send, byte/time and scan limits. Far blocks still arrive
as ordinary Endless page data; this implementation does not reduce network
payloads to LOD-only summaries.

Source-owned section revisions invalidate proxies even when a renderer replaces
vanilla dirty callbacks. Page replacement also invalidates removed sections.
Dense packet replacement invalidates its column. Horizontal unload removes
cached geometry, and level changes/reconnects release its GPU resources.

The proxy cache targets 32 MiB and 16,384 sections; up to two newly built
sections can briefly exceed the retention target before eviction. Dense or highly
varied scenes that exhaust the budget can evict distant proxies.
The combined visible mesh uses one draw call and a separate GPU buffer/staging
buffer, each bounded by the retained vertex budget. Unchanged visible geometry
reuses its buffer; camera movement changes a relative anchor uniform. An
additional screen-sized 32-bit depth texture preserves ordering beyond the native
far plane while sampling native depth for foreground occlusion. The pass shares
the final color texture and never clears or writes native depth. GPU framebuffer,
texture/sampler bindings, viewport, shader program, depth state, blending and
culling are restored after
the pass. Dense worlds, highly varied materials and rapid edits can still cost
more than a simple tower scene; this is not a universal performance guarantee.

## Distant Horizons source study

The implementation is independent MIT-licensed Endless code. Distant Horizons is
not a dependency and its source is not bundled. The source study used the official
[wrapper repository](https://gitlab.com/distant-horizons-team/distant-horizons)
at `0269fb6351850fca8f00756588e16785cc5d15cc`, and its core submodule at
`85c2176993f79eea2909209081b1f81c46abbf00`.

Relevant core files are `render/QuadTree/LodQuadTree.java`,
`dataObjects/transformers/FullDataToRenderDataTransformer.java`,
`dataObjects/render/ColumnRenderSource.java` and `util/FullDataPointUtil.java`.
The useful principles are distance-dependent detail, reduced geometry and
retained render data. Its existing packed full-data points reserve 12 bits each
for relative minimum Y and height, which cannot directly cover Endless' logical
envelope. Endless instead reduces section-local geometry and retains full
section coordinates. No DH implementation code was copied.

## Performance evidence

Performance acceptance requires matched client settings and a visible distant
endpoint plus a distant edited marker. A successful build, a recording's frame
rate, or a blank/clipped scene does not establish improved rendering performance.
Measurements and supported runtime scope are recorded with the benchmark results.
