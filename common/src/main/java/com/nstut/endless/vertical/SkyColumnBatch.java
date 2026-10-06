package com.nstut.endless.vertical;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/** Render-thread queue: neighboring exposure halos share one refresh per drain. */
public final class SkyColumnBatch {
    private final Set<Long> columns = new HashSet<>();
    private final Set<Long> sources = new HashSet<>();
    public boolean add(int x, int z) {
        if (!sources.add(((long) x & 0xffffffffL) | ((long) z << 32))) return false;
        boolean empty = columns.isEmpty();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            columns.add(((long) (x + dx) & 0xffffffffL) | ((long) (z + dz) << 32));
        return empty;
    }
    public boolean isEmpty() { return columns.isEmpty(); }
    public int drain(BiConsumer<Integer, Integer> refresh) {
        var pending = new HashSet<>(columns);
        columns.clear();
        sources.clear();
        for (long key : pending) refresh.accept((int) key, (int) (key >> 32));
        return pending.size();
    }
}
