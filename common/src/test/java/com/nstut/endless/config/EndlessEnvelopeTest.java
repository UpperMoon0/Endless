package com.nstut.endless.config;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the config envelope to vanilla's DimensionType guard band. Vanilla
 * reserves one 16-block section at each packed-Y edge so that neighboring-block
 * operations (BlockPos.offset, light propagation) on the top or bottom block
 * cannot wrap into the opposite end of the 12-bit Y space.
 *
 * Separate from {@link EndlessConfigTest} because DimensionType's static
 * initialization requires vanilla registry bootstrap.
 */
class EndlessEnvelopeTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void minMatchesDimensionTypeMinY() {
        assertEquals(DimensionType.MIN_Y, EndlessConfig.MIN_BUILD_HEIGHT_MIN,
            "minBuildHeight floor must equal DimensionType.MIN_Y");
    }

    @Test
    void maxMatchesDimensionTypeMaxYPlusOne() {
        assertEquals(DimensionType.MAX_Y + 1, EndlessConfig.MAX_BUILD_HEIGHT_MAX,
            "maxBuildHeight ceiling must equal DimensionType.MAX_Y + 1 (exclusive)");
    }
}
