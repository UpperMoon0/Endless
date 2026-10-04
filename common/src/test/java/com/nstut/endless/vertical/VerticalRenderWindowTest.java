package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerticalRenderWindowTest {
    @Test void staysBoundedAtBothMillionScaleExtremesAndDuringRepeatedRebases() {
        var window = new VerticalRenderWindow();
        for (int camera : new int[] {0, 20, 32, 62500, -62500, 499999, -500000, 0, 62500}) {
            assertTrue(window.update(camera, -500000, 500000));
            assertEquals(32, window.maxSection() - window.minSection());
            assertTrue(window.contains(camera));
            assertFalse(window.contains(window.minSection() - 1));
            assertFalse(window.contains(window.maxSection()));
        }
    }

    @Test void hysteresisKeepsOverlappingWindowStable() {
        var window = new VerticalRenderWindow();
        window.update(0, -500000, 500000);
        assertFalse(window.update(8, -500000, 500000));
        assertEquals(-16, window.minSection());
        assertTrue(window.update(9, -500000, 500000));
        assertEquals(-7, window.minSection());
        assertEquals(25, window.maxSection());
    }

    @Test void narrowAndChangedWorldBoundsAreRespected() {
        var window = new VerticalRenderWindow();
        window.update(-62500, -4, 20);
        assertEquals(-4, window.minSection());
        assertEquals(20, window.maxSection());
        assertFalse(window.update(62500, -4, 20));
        assertTrue(window.update(62500, 16, 40));
        assertEquals(16, window.minSection());
        assertEquals(40, window.maxSection());
        assertThrows(IllegalArgumentException.class, () -> window.update(0, 0, 0));
    }
}
