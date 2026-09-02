package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;

/**
 * Logical build range for the sparse v0.5 engine.
 *
 * <p>The fixed constants are the representation ceiling, not the active world
 * limit. The active logical range comes from the normalized server/world config
 * through {@link EndlessHeights}. Vanilla dense arrays remain separately
 * bounded to the legacy-safe core.</p>
 */
public final class EndlessLogicalHeights {
    /** Fixed v0.5 representation envelope, kept inside signed SectionPos Y. */
    public static final int MIN_BUILD_HEIGHT = EndlessConfig.MIN_BUILD_HEIGHT_MIN;
    public static final int MAX_BUILD_HEIGHT = EndlessConfig.MAX_BUILD_HEIGHT_MAX;

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

    /** True only inside the currently configured/effective logical build range. */
    public static boolean contains(int y) {
        return !EndlessHeights.isOutsideBuildHeight(y);
    }

    /** True when a buildable Y must be routed outside the bounded dense core. */
    public static boolean isSparseBuildHeight(int y) {
        return contains(y) && EndlessHeights.isOutsideDenseBuildHeight(y);
    }

    /** True anywhere the v0.5 sparse representation can safely address. */
    public static boolean isRepresentable(int y) {
        return y >= MIN_BUILD_HEIGHT && y < MAX_BUILD_HEIGHT;
    }

    public static boolean isOutsideBuildHeight(int y) {
        return !contains(y);
    }

    /** First section in the currently configured/effective logical range. */
    public static int minSection() {
        return Math.floorDiv(EndlessHeights.getMinBuildHeight(), 16);
    }

    /** Exclusive last section in the currently configured/effective logical range. */
    public static int maxSectionExclusive() {
        return Math.floorDiv(EndlessHeights.getMaxBuildHeight() - 1, 16) + 1;
    }

    public static int representableMinSection() {
        return Math.floorDiv(MIN_BUILD_HEIGHT, 16);
    }

    public static int representableMaxSectionExclusive() {
        return Math.floorDiv(MAX_BUILD_HEIGHT - 1, 16) + 1;
    }

    public static boolean needsExtendedBlockPosEncoding(int y) {
        return active && (y < -2048 || y > 2047);
    }
}
