package com.nstut.endless.vertical.lod;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HorizontalLodRangeTest {
    @Test void distantVerticalSectionsDoNotAddHorizontalCornerColumns() {
        assertTrue(HorizontalLodRange.contains(56.5,-127.5,3,0,8));
        assertTrue(HorizontalLodRange.contains(56.5,-127.5,2,0,8));
        assertFalse(HorizontalLodRange.contains(56.5,-127.5,5,0,8));
        assertFalse(HorizontalLodRange.contains(56.5,-127.5,3,1,8));
    }
    @Test void negativeAndLargeCoordinatesHaveTheSameLocalCoverage() {
        for (int offset : new int[]{-30_000_000,0,30_000_000}) {
            assertTrue(HorizontalLodRange.contains(offset+8.25,offset+8.5,offset/16,offset/16,1));
            assertFalse(HorizontalLodRange.contains(offset+8.25,offset+8.5,offset/16+2,offset/16+2,1));
        }
    }
}
