package com.nstut.endless.vertical;

import java.util.LinkedHashSet;
import java.util.Set;

/** Server-thread queue: never serialize native machine inventories inside their ticks. */
public final class SparseChunkUpdateQueue {
    private static final Set<Pending> pending = new LinkedHashSet<>();
    private SparseChunkUpdateQueue() {}
    public interface Pending { void endless$flushSparseUpdates(); }
    public static void enqueue(Pending update) { pending.add(update); }
    public static void flush() {
        var batch = Set.copyOf(pending); pending.clear();
        // Serialization can itself queue an update; keep that for the next tick.
        for (Pending update : batch) update.endless$flushSparseUpdates();
    }
    public static void clear() { pending.clear(); }
}
