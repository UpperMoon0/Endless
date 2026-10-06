package com.nstut.endless.compat;

import com.nstut.endless.vertical.SkyColumnBatch;
import com.nstut.endless.vertical.VerticalRenderWindow;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashSet;
import java.util.Set;

/** Render-thread lifecycle and cache policy shared by the Sodium 0.6/0.8 adapters. */
public final class RendererSectionState {
    public interface NativeSections {
        void endless$invalidateSnapshot(int x, int y, int z);
        void endless$addSection(int x, int y, int z);
        void endless$removeSection(int x, int y, int z);
        boolean endless$rebuildSection(int x, int y, int z);
        boolean endless$needsDenseRebuild(int x, int y, int z);
        void endless$setBounds(int min, int max);
        void endless$resetGraph();
        void endless$invalidatePointLight();
    }

    private final NativeSections nativeSections;
    private final VerticalRenderWindow window = new VerticalRenderWindow();
    private final LongSet readyChunks = new LongOpenHashSet();
    private final SkyColumnBatch denseSky = new SkyColumnBatch();
    private record SectionKey(int x, int y, int z) {}
    private final Set<SectionKey> deferredRebuilds = new HashSet<>();

    public RendererSectionState(NativeSections nativeSections) { this.nativeSections = nativeSections; }

    public void queueDenseSky(int x, int z) {
        // Invalidate immediately, then again at drain for queries between edits.
        if (denseSky.add(x, z)) nativeSections.endless$invalidatePointLight();
    }

    public int flushDenseSky() {
        if (!denseSky.isEmpty()) nativeSections.endless$invalidatePointLight();
        return denseSky.drain((x, z) -> refreshSkyColumn(x, z, true));
    }

    public void invalidateSkyColumns(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            refreshSkyColumn(x + dx, z + dz, false);
    }

    private void refreshSkyColumn(int x, int z, boolean dense) {
        boolean ready = readyChunks.contains(key(x, z));
        // WorldSlice caches one section on each side of the terrain window.
        for (int y = window.minSection() - 1; y <= window.maxSection(); y++) {
            nativeSections.endless$invalidateSnapshot(x, y, z);
            if (ready && window.contains(y) && (!dense || nativeSections.endless$needsDenseRebuild(x, y, z))
                && !nativeSections.endless$rebuildSection(x, y, z)) deferRebuild(x, y, z);
        }
    }

    public void addChunk(int x, int z) {
        if (!readyChunks.add(key(x, z))) return;
        for (int y = window.minSection(); y < window.maxSection(); y++) {
            nativeSections.endless$invalidateSnapshot(x, y, z);
            nativeSections.endless$addSection(x, y, z);
        }
    }

    public void removeChunk(int x, int z) {
        if (!readyChunks.remove(key(x, z))) return;
        deferredRebuilds.removeIf(section -> section.x == x && section.z == z);
        for (int y = window.minSection(); y < window.maxSection(); y++) {
            nativeSections.endless$removeSection(x, y, z);
            nativeSections.endless$invalidateSnapshot(x, y, z);
        }
    }

    public void updateWindow(int camera, int logicalMin, int logicalMax) {
        int previousMin = window.minSection(), previousMax = window.maxSection();
        if (!window.update(camera, logicalMin, logicalMax)) {
            retryDeferredRebuilds();
            return;
        }
        nativeSections.endless$setBounds(window.minSection(), window.maxSection());
        for (long key : readyChunks) {
            int x = (int) key, z = (int) (key >> 32);
            for (int y = previousMin; y < previousMax; y++) if (!window.contains(y)) {
                // Native removal cancels builds; native upload rejects disposed results.
                nativeSections.endless$removeSection(x, y, z);
                nativeSections.endless$invalidateSnapshot(x, y, z);
            }
            for (int y = window.minSection(); y < window.maxSection(); y++) if (y < previousMin || y >= previousMax) {
                nativeSections.endless$invalidateSnapshot(x, y, z);
                nativeSections.endless$addSection(x, y, z);
            }
        }
        nativeSections.endless$resetGraph();
        retryDeferredRebuilds();
    }

    public void deferRebuild(int x, int y, int z) {
        if (readyChunks.contains(key(x, z)) && window.contains(y)) deferredRebuilds.add(new SectionKey(x, y, z));
    }

    private void retryDeferredRebuilds() {
        var iterator = deferredRebuilds.iterator();
        while (iterator.hasNext()) {
            var section = iterator.next();
            if (!readyChunks.contains(key(section.x, section.z)) || !window.contains(section.y)
                || nativeSections.endless$rebuildSection(section.x, section.y, section.z)) iterator.remove();
        }
    }

    private static long key(int x, int z) { return ((long) x & 0xffffffffL) | ((long) z << 32); }
}
