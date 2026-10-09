# 0.9.3 (unreleased): uniform generated sections

Expose `VerticalSectionFactory.uniform` on the shared Minecraft 1.20.1/1.21.1
engine API. World generators can create worker-local native sections using one
palette entry instead of 4,096 individual block writes. Every call creates
independent block/biome storage and uses the native section constructor to
initialize nonempty, block-ticking and fluid-ticking counts.

This does not admit a page, change dense arrays or select terrain. Tidal Terror
owns the decision that a foundation section is uniform and still runs its
original biome and coral decorators. The factory is not a Minecraft 26.1.2 API;
that line uses a different paletted-container factory and has no deep Tidal
Terror adapter yet.

`NativeUniformSectionChecks` compares AIR, WATER, DEEPSLATE and living coral
against sections filled through native individual writes, exercises section
codec round trips and verifies mutation isolation. Tidal Terror's native
fresh/restart fixture invokes these checks alongside its terrain, feature,
lighting and spawning regressions. Performance evidence is recorded separately
in the Tidal Terror PR because the improvement is exercised by that generator.
