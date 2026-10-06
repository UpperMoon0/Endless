package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InitialBuildUpdatesTest {
    private static class Target implements InitialBuildUpdates.Target {
        boolean current = true, built;
        int attempts, accepted;
        public boolean endless$isCurrentInitialSection(int x, int y, int z) { return current; }
        public boolean endless$rebuildInitialSection(int x, int y, int z) {
            attempts++;
            if (!built) return false;
            accepted++;
            return true;
        }
    }

    @Test void duplicateEditsWaitForUploadThenRebuildOnce() {
        var target = new Target();
        var updates = new InitialBuildUpdates(target);
        for (int i = 0; i < 120; i++) updates.add(-7, 62500, 9);
        updates.retry();
        assertEquals(1, target.attempts);
        assertEquals(0, target.accepted);
        assertTrue(updates.contains(-7, 62500, 9));
        target.built = true;
        updates.retry();
        updates.retry();
        assertEquals(1, target.accepted);
        assertFalse(updates.contains(-7, 62500, 9));
    }

    @Test void unloadAndWindowDepartureDiscardNotifications() {
        var target = new Target();
        var updates = new InitialBuildUpdates(target);
        updates.add(4, 62500, 4);
        updates.discardColumn(4, 4);
        target.built = true;
        updates.retry();
        assertEquals(0, target.attempts);
        updates.add(4, 62500, 4);
        target.current = false;
        updates.retry();
        assertFalse(updates.contains(4, 62500, 4));
        updates.add(4, 62500, 4);
        assertFalse(updates.contains(4, 62500, 4));
        assertEquals(0, target.attempts);
    }

    @Test void fullCoordinatesStayDistinctAtLogicalHeightExtremes() {
        var target = new Target();
        var updates = new InitialBuildUpdates(target);
        updates.add(-1875000, -500000, 1875000);
        updates.add(-1875000, 500000, 1875000);
        updates.add(1875000, -500000, -1875000);
        updates.discardColumn(-1875000, 1875000);
        assertFalse(updates.contains(-1875000, -500000, 1875000));
        assertFalse(updates.contains(-1875000, 500000, 1875000));
        assertTrue(updates.contains(1875000, -500000, -1875000));
        target.built = true;
        updates.retry();
        assertEquals(1, target.accepted);
    }
}
