package com.nstut.endless.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionArrayPoolTest {

    @Test
    void poolStartsEmpty() {
        assertEquals(0, SectionArrayPool.getPoolSize());
    }

    @Test
    void checkoutReturnsNull_whenPoolEmpty() {
        assertNull(SectionArrayPool.checkout());
    }

    @Test
    void nullSection_isRejected() {
        int sizeBefore = SectionArrayPool.getPoolSize();
        SectionArrayPool.reclaimSection(null);
        assertEquals(sizeBefore, SectionArrayPool.getPoolSize(),
            "Reclaiming null should not change pool size");
    }

    @Test
    void checkoutConsistentAfterMultipleCalls() {
        for (int i = 0; i < 5; i++) {
            assertNull(SectionArrayPool.checkout(),
                "Pool should stay empty with no reclaims");
        }
    }
}
