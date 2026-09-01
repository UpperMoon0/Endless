package com.nstut.endless.heights;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndlessHeightsTest {

    @Test
    void merge_allowsExpansion() {
        int[] merged = EndlessHeights.mergeRange(-64, 320, -1024, 1024);
        assertEquals(-1024, merged[0]);
        assertEquals(1024, merged[1]);
    }

    @Test
    void merge_rejectsShrink() {
        // Config narrower than the world range: world wins on both ends so
        // saved sections stay reachable.
        int[] merged = EndlessHeights.mergeRange(-1024, 1024, -64, 320);
        assertEquals(-1024, merged[0]);
        assertEquals(1024, merged[1]);
    }

    @Test
    void merge_expandsOnlyTheWidenedEnd() {
        int[] merged = EndlessHeights.mergeRange(-1024, 320, -512, 1024);
        assertEquals(-1024, merged[0], "saved min kept (config would shrink)");
        assertEquals(1024, merged[1], "config max expands");
    }

    @Test
    void merge_unchangedWhenEqual() {
        int[] merged = EndlessHeights.mergeRange(-64, 320, -64, 320);
        assertEquals(-64, merged[0]);
        assertEquals(320, merged[1]);
    }
}
