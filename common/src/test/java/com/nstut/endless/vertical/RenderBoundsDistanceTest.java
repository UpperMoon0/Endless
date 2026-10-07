package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderBoundsDistanceTest {
    @Test void lowerCabinKeepsRopeWhosePulleyIsFiveHundredBlocksAboveCamera() {
        assertTrue(RenderBoundsDistance.within(84.5, 111.6, -45.5, 80, 100, -40, 81, 601, -39, 64));
    }
    @Test void anOriginWithinViewDistanceDoesNotMakeDistantGeometryVisible() {
        assertFalse(RenderBoundsDistance.within(84.5, 111.6, -45.5, 80, 500, -40, 81, 601, -39, 64));
    }
    @Test void preservesStrictDistanceBoundaryAndDiagonalDistance() {
        assertFalse(RenderBoundsDistance.within(0, 0, 0, 64, 0, 0, 65, 1, 1, 64));
        assertTrue(RenderBoundsDistance.within(0, 0, 0, 63.99, 0, 0, 65, 1, 1, 64));
        assertFalse(RenderBoundsDistance.within(0, 0, 0, 50, 50, 0, 51, 51, 1, 64));
    }
    @Test void worksAtPositiveAndNegativeMillionScaleCoordinates() {
        for (double y : new double[]{1_000_000, -1_000_000}) {
            assertTrue(RenderBoundsDistance.within(0, y, 0, -1, y-2, -1, 1, y+500, 1, 64));
            assertFalse(RenderBoundsDistance.within(0, y-100, 0, -1, y, -1, 1, y+500, 1, 64));
        }
    }
    @Test void invalidRadiusAndNanCannotBypassCulling() {
        assertFalse(RenderBoundsDistance.within(0,0,0,-1,-1,-1,1,1,1,0));
        assertFalse(RenderBoundsDistance.within(Double.NaN,0,0,-1,-1,-1,1,1,1,64));
    }
}
