# 0.9.3 (unreleased): logical natural-spawn bounds

Native spawn dispatch checked selected positions against the dense chunk floor,
rejecting habitats below Y -64 even when those positions were inside Endless's
configured logical range. Use the logical lower bound for that dispatch guard
on all five supported targets, retaining native behavior when sparse mode is
inactive. Dense array origins and height remain unchanged.

This hook accepts positions selected by vanilla or a world-generation mod. It
does not sample the entire logical range, change species rules or increase caps.
Tidal Terror owns its bounded basin sampler and canopy habitat restrictions.

Validation: Forge 1.20.1 packaging, shared 1.21.1 and NeoForge 26.1.2 compilation,
and the 1.20.1/1.21.1 common unit suites passed locally. The Tidal Terror deep
fixture exercised actual native spawn dispatch with a player in generated
sparse terrain: all four custom species, vanilla fish above/below the canopy,
and a naturally selected deep Drowned. Native predicate refusal still blocked
spawns. Full new-head CI remains required before promotion.
