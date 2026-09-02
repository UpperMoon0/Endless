package com.nstut.endless.heights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndlessHeightsTest {

    @Test
    void merge_allowsDenseExpansion() {
        int[] merged = EndlessHeights.mergeRange(-64, 320, -1024, 1024);
        assertEquals(-1024, merged[0]);
        assertEquals(1024, merged[1]);
    }

    @Test
    void merge_rejectsDenseShrink() {
        int[] merged = EndlessHeights.mergeRange(-1024, 1024, -64, 320);
        assertEquals(-1024, merged[0]);
        assertEquals(1024, merged[1]);
    }

    @Test
    void merge_expandsOnlyTheWidenedDenseEnd() {
        int[] merged = EndlessHeights.mergeRange(-1024, 320, -512, 1024);
        assertEquals(-1024, merged[0]);
        assertEquals(1024, merged[1]);
    }

    @Test
    void fullLogicalEnvelopeProjectsToBoundedDenseCore() {
        int[] dense = EndlessHeights.denseRangeForLogical(-8_000_000, 8_000_000);
        assertEquals(-2032, dense[0]);
        assertEquals(2032, dense[1]);
    }

    @Test
    void narrowerLogicalRangeCanUseNarrowerDenseCore() {
        int[] dense = EndlessHeights.denseRangeForLogical(-1024, 1024);
        assertEquals(-1024, dense[0]);
        assertEquals(1024, dense[1]);
    }

    @Test
    void logicalShrinkDoesNotRequireDenseShrink() {
        EndlessHeights.applyEffective(-64, 320, -1024, 1024);
        try {
            assertTrue(EndlessHeights.isOutsideBuildHeight(500),
                "configured logical max must reject commands/building at Y=500");
            assertFalse(EndlessHeights.isOutsideDenseBuildHeight(500),
                "persisted dense core may remain wider solely for Anvil safety");
            assertEquals(-64, EndlessHeights.getMinBuildHeight());
            assertEquals(320, EndlessHeights.getMaxBuildHeight());
            assertEquals(-1024, EndlessHeights.getDenseMinBuildHeight());
            assertEquals(1024, EndlessHeights.getDenseMaxBuildHeight());
        } finally {
            EndlessHeights.resetToLocalConfig();
        }
    }
}
