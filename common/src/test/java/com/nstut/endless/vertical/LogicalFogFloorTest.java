package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogicalFogFloorTest {
    // Vanilla's 1.20/1.21 void-color multiplier before status-effect handling.
    private static double vanillaVisibility(double cameraY, int minimum) {
        double fade = Math.max(0, Math.min(1, (cameraY - minimum) / 32));
        return fade * fade;
    }

    @Test void deepAirAndWaterAreNotBelowTheVoidBoundary() {
        assertEquals(0, vanillaVisibility(-448, -64)); // Reproduces the old floor bug.
        for (int minimum : new int[]{-512, -1024, -8000000}) {
            int floor = LogicalFogFloor.select(true, minimum, -64);
            assertEquals(1, vanillaVisibility(-448, floor));
            // 26.1 uses the inverse onset-distance formula at the same boundary.
            double darkness = Math.max(0, Math.min(1, (32 + floor - (-448.0)) / 32));
            assertEquals(0, darkness);
        }
    }

    @Test void voidFadeStillExistsAtTheConfiguredLowerBoundary() {
        int floor = LogicalFogFloor.select(true, -1024, -64);
        assertEquals(0, vanillaVisibility(-1025, floor));
        assertEquals(0, vanillaVisibility(-1024, floor));
        assertEquals(.25, vanillaVisibility(-1008, floor));
        assertEquals(1, vanillaVisibility(-992, floor));
    }

    @Test void legacyWorldIgnoresStaleExtendedConfiguration() {
        for (int nativeFloor : new int[]{-64, -2032, 0}) {
            int floor = LogicalFogFloor.select(false, -8000000, nativeFloor);
            assertEquals(.25, vanillaVisibility(nativeFloor + 16, floor));
            assertEquals(0, vanillaVisibility(nativeFloor - 1, floor));
        }
    }

    @Test void vanillaRangeHasTheSameFadeInSparseMode() {
        for (int y = -100; y <= 100; y++) {
            assertEquals(vanillaVisibility(y, -64),
                vanillaVisibility(y, LogicalFogFloor.select(true, -64, -64)));
        }
    }

    @Test void configuredRangeRatherThanRepresentationCeilingDefinesTheVoid() {
        int floor = LogicalFogFloor.select(true, 64, -64);
        assertEquals(0, vanillaVisibility(63, floor));
        assertEquals(.25, vanillaVisibility(80, floor));
        assertEquals(1, vanillaVisibility(96, floor));
    }
}
