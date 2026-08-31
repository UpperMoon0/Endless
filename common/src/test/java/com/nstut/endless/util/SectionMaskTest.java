package com.nstut.endless.util;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

class SectionMaskTest {

    @Test
    void emptyMaskMeansEmptySpace() {
        assertTrue(SectionMask.isEmptyInRange(new BitSet(), 0, 15));
    }

    @Test
    void missingHigherBitCountsAsEmpty() {
        // Regression: nextSetBit returns -1 when no later bit exists; -1 must be
        // treated as "nothing at or above minIdx", not as "inside the range".
        BitSet mask = new BitSet();
        mask.set(2);
        assertTrue(SectionMask.isEmptyInRange(mask, 10, 20),
            "range above all non-empty sections must be empty");
        assertFalse(SectionMask.isEmptyInRange(mask, 2, 20),
            "range containing a non-empty section must not be empty");
    }

    @Test
    void invertedRangeCountsAsEmpty() {
        BitSet mask = new BitSet();
        mask.set(5);
        assertTrue(SectionMask.isEmptyInRange(mask, 10, 2));
    }

    @Test
    void boundaryIndicesAreInclusive() {
        BitSet mask = new BitSet();
        mask.set(5);
        assertFalse(SectionMask.isEmptyInRange(mask, 5, 5));
        assertTrue(SectionMask.isEmptyInRange(mask, 6, 9));
    }
}
