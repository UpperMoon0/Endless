package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;

/**
 * Pure classification for pre-v0.4 worlds that do not yet have
 * endless_build_heights.dat. Older versions did not persist the world range,
 * so the only trustworthy automatic-migration input is the raw config that
 * existed before v0.4 clamps it to the guarded envelope.
 */
public final class LegacyWorldMigration {
    /** Signed-byte section Y can represent exactly these raw block bounds. */
    public static final int RAW_MIN_BUILD_HEIGHT = -2048;
    public static final int RAW_MAX_BUILD_HEIGHT = 2048;

    private LegacyWorldMigration() {
    }

    public enum Status {
        MIGRATE,
        INSPECT_EDGE_SECTIONS,
        REFUSE
    }

    public record Resolution(
        Status status,
        int migratedMin,
        int migratedMax,
        boolean inspectBottomEdge,
        boolean inspectTopEdge,
        String reason
    ) {
        public boolean canMigrate() {
            return status == Status.MIGRATE;
        }
    }

    /**
     * Classify a raw legacy config before touching region files.
     *
     * <p>Bounds are normalized to section boundaries without applying the new
     * v0.4 guard band first. This preserves evidence such as [-2048, 2048),
     * which would otherwise be silently rewritten to [-2032, 2032) before we
     * had a chance to inspect the two unsafe edge sections.</p>
     */
    public static Resolution classify(int rawMin, int rawMax) {
        long min = (long) rawMin & ~15L;
        long max = ((long) rawMax + 15L) & ~15L;

        if (min >= max) {
            return refuse("legacy config has an empty or inverted build range");
        }

        long span = max - min;
        if (span > 4096L || min < RAW_MIN_BUILD_HEIGHT || max > RAW_MAX_BUILD_HEIGHT) {
            return refuse("legacy range [" + min + ", " + max
                + ") cannot be reconstructed safely from the signed-byte section Y format");
        }

        boolean inspectBottom = min < EndlessConfig.MIN_BUILD_HEIGHT_MIN;
        boolean inspectTop = max > EndlessConfig.MAX_BUILD_HEIGHT_MAX;
        int migratedMin = (int) Math.max(min, EndlessConfig.MIN_BUILD_HEIGHT_MIN);
        int migratedMax = (int) Math.min(max, EndlessConfig.MAX_BUILD_HEIGHT_MAX);

        if (inspectBottom || inspectTop) {
            return new Resolution(
                Status.INSPECT_EDGE_SECTIONS,
                migratedMin,
                migratedMax,
                inspectBottom,
                inspectTop,
                "legacy range touches the raw edge section(s); region data must be inspected before migration"
            );
        }

        return new Resolution(
            Status.MIGRATE,
            (int) min,
            (int) max,
            false,
            false,
            "legacy range is fully inside the guarded envelope"
        );
    }

    /** Finish an edge-section migration after the region scan. */
    public static Resolution resolveEdgeInspection(
        Resolution preliminary,
        boolean bottomEdgeHasMeaningfulData,
        boolean topEdgeHasMeaningfulData
    ) {
        if (preliminary.status != Status.INSPECT_EDGE_SECTIONS) {
            return preliminary;
        }
        if (preliminary.inspectBottomEdge && bottomEdgeHasMeaningfulData) {
            return refuse("legacy section Y=-128 contains meaningful block data outside the v0.4 safe envelope");
        }
        if (preliminary.inspectTopEdge && topEdgeHasMeaningfulData) {
            return refuse("legacy section Y=127 contains meaningful block data outside the v0.4 safe envelope");
        }
        return new Resolution(
            Status.MIGRATE,
            preliminary.migratedMin,
            preliminary.migratedMax,
            false,
            false,
            "legacy edge sections contain no meaningful block data; migration may clamp to the guarded envelope"
        );
    }

    private static Resolution refuse(String reason) {
        return new Resolution(Status.REFUSE, 0, 0, false, false, reason);
    }
}
