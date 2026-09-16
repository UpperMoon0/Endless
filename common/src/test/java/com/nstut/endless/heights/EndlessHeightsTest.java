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
    void freshExtendedLogicalRangeKeepsVanillaDenseCore() {
        int[] dense = EndlessHeights.denseRangeForLogical(-8_000_000, 8_000_000);
        assertEquals(-64, dense[0]);
        assertEquals(320, dense[1]);
    }

    @Test
    void freshModerateLogicalRangeStillUsesSparseOutsideVanillaCore() {
        int[] dense = EndlessHeights.denseRangeForLogical(-1024, 1024);
        assertEquals(-64, dense[0]);
        assertEquals(320, dense[1]);
        assertTrue(-1024 < dense[0]);
        assertTrue(1023 >= dense[1]);
    }

    @Test
    void legacyDenseCoreReservesSkyLightGuardCapacity() {
        EndlessHeights.applyEffective(-1_048_576, 1_048_576, -2032, 2032);
        try {
            assertEquals(4064, EndlessHeights.getHeight());
            assertEquals(13, EndlessHeights.skyLightStorageBits(12));
            long maxEncoded = (1L << EndlessHeights.skyLightStorageBits(12)) - 1L;
            assertTrue(maxEncoded >= EndlessHeights.getHeight() + 32L);
        } finally {
            EndlessHeights.resetToLocalConfig();
        }
    }

    @Test
    void vanillaDenseCoreDoesNotGrowSkyLightStorage() {
        EndlessHeights.applyEffective(-1_048_576, 1_048_576, -64, 320);
        try {
            assertEquals(9, EndlessHeights.skyLightStorageBits(9));
        } finally {
            EndlessHeights.resetToLocalConfig();
        }
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
