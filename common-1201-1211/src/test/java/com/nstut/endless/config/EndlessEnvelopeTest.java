package com.nstut.endless.config;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the sparse logical envelope and vanilla-compatible dense core separately. */
class EndlessEnvelopeTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void denseMinStillMatchesDimensionTypeGuardBand() {
        assertEquals(DimensionType.MIN_Y, EndlessConfig.DENSE_MIN_BUILD_HEIGHT,
            "dense min must remain on vanilla's guarded DimensionType envelope");
    }

    @Test
    void denseMaxStillMatchesDimensionTypeGuardBand() {
        assertEquals(DimensionType.MAX_Y + 1, EndlessConfig.DENSE_MAX_BUILD_HEIGHT,
            "dense max must remain on vanilla's guarded DimensionType envelope");
    }

    @Test
    void logicalEnvelopeIsWiderThanDenseCore() {
        assertEquals(-8_000_000, EndlessConfig.MIN_BUILD_HEIGHT_MIN);
        assertEquals(8_000_000, EndlessConfig.MAX_BUILD_HEIGHT_MAX);
        assertTrue(EndlessConfig.MIN_BUILD_HEIGHT_MIN < EndlessConfig.DENSE_MIN_BUILD_HEIGHT);
        assertTrue(EndlessConfig.MAX_BUILD_HEIGHT_MAX > EndlessConfig.DENSE_MAX_BUILD_HEIGHT);
    }

    @Test
    void denseCoreStillUsesAtMost254Sections() {
        assertEquals(254,
            (EndlessConfig.DENSE_MAX_BUILD_HEIGHT - EndlessConfig.DENSE_MIN_BUILD_HEIGHT) / 16);
    }
}
