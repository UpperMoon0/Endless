package com.nstut.endless.compat;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RendererSectionStateTest {
    private record Section(int x, int y, int z) {}
    private static final class Native implements RendererSectionState.NativeSections {
        final List<Section> added = new ArrayList<>(), removed = new ArrayList<>(), invalidated = new ArrayList<>(), rebuilt = new ArrayList<>();
        int min, max, resets, pointInvalidations;
        public void endless$invalidateSnapshot(int x, int y, int z) { invalidated.add(new Section(x, y, z)); }
        public void endless$addSection(int x, int y, int z) { added.add(new Section(x, y, z)); }
        public void endless$removeSection(int x, int y, int z) { removed.add(new Section(x, y, z)); }
        public void endless$rebuildSection(int x, int y, int z) { rebuilt.add(new Section(x, y, z)); }
        public boolean endless$needsDenseRebuild(int x, int y, int z) { return y == 0; }
        public void endless$setBounds(int min, int max) { this.min = min; this.max = max; }
        public void endless$resetGraph() { resets++; }
        public void endless$invalidatePointLight() { pointInvalidations++; }
        void clearSections() { added.clear(); removed.clear(); invalidated.clear(); rebuilt.clear(); }
    }

    @Test void rebasingOnlyRemovesDepartingNodesAndAddsEnteringNodes() {
        var nativeSections = new Native();
        var state = new RendererSectionState(nativeSections);
        state.updateWindow(0, -1000, 1000);
        state.addChunk(-7, 9);
        assertEquals(32, nativeSections.added.size());
        nativeSections.clearSections();
        state.updateWindow(8, -1000, 1000);
        assertEquals(1, nativeSections.resets); // Hysteresis avoids needless teardown.
        state.updateWindow(9, -1000, 1000);
        assertEquals(-7, nativeSections.min);
        assertEquals(25, nativeSections.max);
        assertEquals(9, nativeSections.removed.size());
        assertEquals(9, nativeSections.added.size());
        assertTrue(nativeSections.removed.stream().allMatch(s -> s.y < -7 && s.x == -7 && s.z == 9));
        assertTrue(nativeSections.added.stream().allMatch(s -> s.y >= 16 && s.x == -7 && s.z == 9));
        assertEquals(18, nativeSections.invalidated.size());
    }

    @Test void unloadStopsRebuildsAndNewManagerDoesNotInheritReadiness() {
        var nativeSections = new Native();
        var state = new RendererSectionState(nativeSections);
        state.updateWindow(0, -1000, 1000);
        state.addChunk(0, 0);
        state.addChunk(0, 0);
        assertEquals(32, nativeSections.added.size());
        state.queueDenseSky(0, 0);
        state.removeChunk(0, 0);
        state.removeChunk(0, 0);
        assertEquals(32, nativeSections.removed.size());
        state.flushDenseSky();
        assertTrue(nativeSections.rebuilt.isEmpty());
        nativeSections.clearSections();
        var nextWorld = new RendererSectionState(nativeSections);
        nextWorld.updateWindow(0, -1000, 1000);
        nextWorld.invalidateSkyColumns(0, 0);
        assertTrue(nativeSections.rebuilt.isEmpty());
        assertEquals(0, nextWorld.flushDenseSky());
    }

    @Test void denseBurstsCoalesceWithPointInvalidationAndSnapshotHalo() {
        var nativeSections = new Native();
        var state = new RendererSectionState(nativeSections);
        state.updateWindow(0, -1000, 1000);
        state.addChunk(0, 0);
        nativeSections.clearSections();
        for (int i = 0; i < 120; i++) state.queueDenseSky(0, 0);
        assertEquals(1, nativeSections.pointInvalidations);
        assertEquals(9, state.flushDenseSky());
        assertEquals(2, nativeSections.pointInvalidations);
        assertEquals(List.of(new Section(0, 0, 0)), nativeSections.rebuilt);
        assertEquals(9 * 34, nativeSections.invalidated.size());
        assertTrue(nativeSections.invalidated.contains(new Section(0, -17, 0)));
        assertTrue(nativeSections.invalidated.contains(new Section(0, 16, 0)));
        assertEquals(0, state.flushDenseSky());
        assertEquals(2, nativeSections.pointInvalidations);
    }

    @Test void sparseExposureRefreshesAllReadyGeometryWithinLogicalBounds() {
        var nativeSections = new Native();
        var state = new RendererSectionState(nativeSections);
        state.updateWindow(0, -2, 3);
        state.addChunk(0, 0);
        nativeSections.clearSections();
        state.invalidateSkyColumns(0, 0);
        assertEquals(5, nativeSections.rebuilt.size());
        assertTrue(nativeSections.rebuilt.stream().allMatch(s -> s.y >= -2 && s.y < 3));
        assertEquals(9 * 7, nativeSections.invalidated.size());
    }
}
