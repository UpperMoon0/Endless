package com.nstut.endless.util;

import java.util.BitSet;

/**
 * Shared helpers for the chunk non-empty-section bitmask.
 */
public final class SectionMask {

    private SectionMask() {
    }

    /**
     * Returns whether every section in the inclusive index range
     * [minIdx, maxIdx] is empty, given a mask of non-empty section indices.
     */
    public static boolean isEmptyInRange(BitSet nonEmpty, int minIdx, int maxIdx) {
        if (minIdx > maxIdx) {
            return true;
        }
        int next = nonEmpty.nextSetBit(minIdx);
        // nextSetBit returns -1 when no later bit exists: an empty range.
        return next < 0 || next > maxIdx;
    }
}
