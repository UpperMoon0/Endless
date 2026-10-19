package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerticalViewDistanceTest {
    @Test void expandedCullingUsesGraphBoundsWhileDefaultKeepsNativeDistance() {
        assertEquals(-2048.25f, VerticalViewDistance.cullingDelta(-2048.25f, 32));
        assertEquals(2048.25f, VerticalViewDistance.cullingDelta(2048.25f, 0));
        assertEquals(0.0f, VerticalViewDistance.cullingDelta(2048.25f, 256));
        assertEquals(0.0f, VerticalViewDistance.cullingDelta(-2048.25f, 512));
    }

    @Test void defaultsAndMaliciousValuesStayBounded() {
        assertEquals(32, VerticalViewDistance.clampSections(Integer.MIN_VALUE));
        assertEquals(512, VerticalViewDistance.clampSections(Integer.MAX_VALUE));
        assertEquals(1, VerticalViewDistance.pageRadius(32));
        assertEquals(5, VerticalViewDistance.pageRadius(256));
        assertEquals(9, VerticalViewDistance.pageRadius(512));
    }

    @Test void serverWindowCoversEveryRenderSectionIncludingHysteresisAtPageSeams() {
        for (int budget : new int[]{32, 64, 128, 256, 512}) {
            for (int camera = -513; camera <= 513; camera++) {
                for (int rebase : new int[]{-8, 0, 8}) {
                    var window = new VerticalRenderWindow();
                    window.update(camera + rebase, -500000, 500000, budget);
                    int cameraPage = Math.floorDiv(camera, 32);
                    int radius = VerticalViewDistance.pageRadius(budget);
                    assertTrue(Math.floorDiv(window.minSection(), 32) >= cameraPage - radius);
                    assertTrue(Math.floorDiv(window.maxSection() - 1, 32) <= cameraPage + radius);
                }
            }
        }
    }

    @Test void expandedPageScanResumesAfterBackpressureUntilDistantControllerArrives() {
        int radius = VerticalViewDistance.pageRadius(256);
        var scan = new PageWindowScan(36, 7, 8, 0, radius);
        var delivered = new java.util.HashSet<String>();
        var pending = new java.util.ArrayDeque<String>();
        for (int ticks = 0; ticks < 10000 && (!scan.isComplete() || !pending.isEmpty()); ticks++) {
            scan.advance(16, (x, z) -> true, (x, page, z) -> {
                if (pending.size() == 2) return false;
                pending.add(x + ":" + page + ":" + z);
                return true;
            });
            if (!pending.isEmpty()) assertTrue(delivered.add(pending.remove()));
        }
        assertTrue(scan.isComplete());
        assertTrue(pending.isEmpty());
        assertEquals(17 * 17 * (radius * 2 + 1), delivered.size());
        assertTrue(delivered.contains("36:4:7"), "Controller at Y=2160 must reach the base viewer");
    }

    @Test void cinematicWindowCanContainEntireElevatorWithoutAllocatingLogicalHeight() {
        var window = new VerticalRenderWindow();
        window.update(200 >> 4, -500000, 500000, 256);
        assertEquals(256, window.maxSection() - window.minSection());
        assertTrue(window.contains(82 >> 4));
        assertTrue(window.contains(2190 >> 4));
        assertFalse(window.contains(window.maxSection()));
        assertTrue(window.update(-499999, -500000, 500000, 256));
        assertEquals(-500000, window.minSection());
        assertEquals(256, window.maxSection() - window.minSection());
        assertTrue(window.update(499999, -500000, 500000, 512));
        assertEquals(500000, window.maxSection());
    }
}
