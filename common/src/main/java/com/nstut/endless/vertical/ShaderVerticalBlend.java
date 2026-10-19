package com.nstut.endless.vertical;

/** Complementary's native/DH seam must follow horizontal distance in an expanded world. */
public final class ShaderVerticalBlend {
    private static final String VERTICAL_FADE = "max(length(playerPos.xz), abs(playerPos.y)) / far";
    private static final String HORIZONTAL_FADE = "length(playerPos.xz) / far";
    private ShaderVerticalBlend() {}

    public static String adapt(String source, int sections) {
        if (VerticalViewDistance.clampSections(sections) <= VerticalViewDistance.DEFAULT_SECTIONS
            || !source.contains("#ifdef DH_BLENDING")) return source;
        // Match the known native-terrain blend only. Do not change the shared far
        // uniform: DH uses it too and increasing it would leave a gap in the LODs.
        return source.replace(VERTICAL_FADE, HORIZONTAL_FADE);
    }
}
