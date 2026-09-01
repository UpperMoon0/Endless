package com.nstut.endless.heights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndlessLogicalHeightsTest {
    private static final int SECTION_Y_MIN = -(1 << 19);
    private static final int SECTION_Y_MAX = (1 << 19) - 1;

    @Test
    void logicalRangeFitsSignedSectionPosYEnvelope() {
        assertTrue(EndlessLogicalHeights.minSection() >= SECTION_Y_MIN);
        assertTrue(EndlessLogicalHeights.maxSectionExclusive() - 1 <= SECTION_Y_MAX);
    }

    @Test
    void rangeIsSectionAlignedAndExclusive() {
        assertEquals(0, Math.floorMod(EndlessLogicalHeights.MIN_BUILD_HEIGHT, 16));
        assertEquals(0, Math.floorMod(EndlessLogicalHeights.MAX_BUILD_HEIGHT, 16));
        assertTrue(EndlessLogicalHeights.contains(EndlessLogicalHeights.MIN_BUILD_HEIGHT));
        assertTrue(EndlessLogicalHeights.contains(EndlessLogicalHeights.MAX_BUILD_HEIGHT - 1));
        assertFalse(EndlessLogicalHeights.contains(EndlessLogicalHeights.MAX_BUILD_HEIGHT));
    }

    @Test
    void extendedBlockPosCodecOnlyNeededOutsidePackedYEnvelope() {
        EndlessLogicalHeights.activate();
        try {
            assertFalse(EndlessLogicalHeights.needsExtendedBlockPosEncoding(-2048));
            assertFalse(EndlessLogicalHeights.needsExtendedBlockPosEncoding(2047));
            assertTrue(EndlessLogicalHeights.needsExtendedBlockPosEncoding(-2049));
            assertTrue(EndlessLogicalHeights.needsExtendedBlockPosEncoding(2048));
        } finally {
            EndlessLogicalHeights.deactivate();
        }
    }
}
