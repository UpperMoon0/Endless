package com.nstut.endless.heights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndlessLogicalHeightsTest {
    private static final int SECTION_Y_MIN = -(1 << 19);
    private static final int SECTION_Y_MAX = (1 << 19) - 1;

    @Test
    void representationEnvelopeFitsSignedSectionPosY() {
        assertTrue(EndlessLogicalHeights.representableMinSection() >= SECTION_Y_MIN);
        assertTrue(EndlessLogicalHeights.representableMaxSectionExclusive() - 1 <= SECTION_Y_MAX);
    }

    @Test
    void representationEnvelopeIsSectionAlignedAndExclusive() {
        assertEquals(0, Math.floorMod(EndlessLogicalHeights.MIN_BUILD_HEIGHT, 16));
        assertEquals(0, Math.floorMod(EndlessLogicalHeights.MAX_BUILD_HEIGHT, 16));
        assertTrue(EndlessLogicalHeights.isRepresentable(EndlessLogicalHeights.MIN_BUILD_HEIGHT));
        assertTrue(EndlessLogicalHeights.isRepresentable(EndlessLogicalHeights.MAX_BUILD_HEIGHT - 1));
        assertFalse(EndlessLogicalHeights.isRepresentable(EndlessLogicalHeights.MAX_BUILD_HEIGHT));
    }

    @Test
    void containsUsesConfiguredEffectiveRangeNotRepresentationCeiling() {
        EndlessHeights.applyEffective(-1024, 1024, -1024, 1024);
        try {
            assertTrue(EndlessLogicalHeights.contains(-1024));
            assertTrue(EndlessLogicalHeights.contains(1023));
            assertFalse(EndlessLogicalHeights.contains(-1025));
            assertFalse(EndlessLogicalHeights.contains(1024));
            assertEquals(-64, EndlessLogicalHeights.minSection());
            assertEquals(64, EndlessLogicalHeights.maxSectionExclusive());
        } finally {
            EndlessHeights.resetToLocalConfig();
        }
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
