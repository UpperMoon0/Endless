package com.nstut.endless.heights;

/**
 * Logical build range for the sparse v0.5 engine.
 *
 * <p>The vanilla LevelHeightAccessor remains bounded to the v0.4 dense core so
 * arrays and O(height) vanilla loops stay safe. Buildability outside that core
 * is controlled separately here and routed through sparse vertical pages.</p>
 */
public final class EndlessLogicalHeights {
    /** Kept inside the signed 20-bit SectionPos Y envelope (in block units). */
    public static final int MIN_BUILD_HEIGHT = -8_000_000;
    public static final int MAX_BUILD_HEIGHT = 8_000_000;

    private static volatile boolean active;

    private EndlessLogicalHeights() {}

    public static boolean isActive() {
        return active;
    }

    public static void activate() {
        active = true;
    }

    public static void deactivate() {
        active = false;
    }

    public static boolean contains(int y) {
        return y >= MIN_BUILD_HEIGHT && y < MAX_BUILD_HEIGHT;
    }

    public static boolean isOutsideBuildHeight(int y) {
        return !contains(y);
    }

    public static int minSection() {
        return Math.floorDiv(MIN_BUILD_HEIGHT, 16);
    }

    public static int maxSectionExclusive() {
        return Math.floorDiv(MAX_BUILD_HEIGHT - 1, 16) + 1;
    }

    public static boolean needsExtendedBlockPosEncoding(int y) {
        return active && (y < -2048 || y > 2047);
    }
}
