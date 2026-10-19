package com.nstut.endless.vertical;

/** Independent, bounded vertical terrain budget; logical world height never sizes a render grid. */
public final class VerticalViewDistance {
    public static final int DEFAULT_SECTIONS = 32;
    public static final int MAX_SECTIONS = 512;
    public static final int HYSTERESIS_SECTIONS = 8;
    private VerticalViewDistance() {}

    public static int clampSections(int sections) {
        return Math.max(DEFAULT_SECTIONS, Math.min(MAX_SECTIONS, sections));
    }

    /** Renderer graphs already bound Y; an expanded window must not inherit the horizontal radius. */
    public static float cullingDelta(float verticalDelta, int sections) {
        return clampSections(sections) > DEFAULT_SECTIONS ? 0.0f : verticalDelta;
    }

    /** Include the window's hysteresis and a camera anywhere inside its current 512-block page. */
    public static int pageRadius(int sections) {
        return (clampSections(sections) + 2 * HYSTERESIS_SECTIONS + 63) / 64;
    }
}
