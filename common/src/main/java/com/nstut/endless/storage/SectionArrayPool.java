package com.nstut.endless.storage;

import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;

public final class SectionArrayPool {
    private static final int MAX_POOLED_SECTIONS = 256;
    private static final Queue<LevelChunkSection> pool = new ArrayDeque<>();
    private static final ReferenceQueue<LevelChunkSection> refQueue = new ReferenceQueue<>();
    private static final ArrayList<SectionPhantomRef> phantomRefs = new ArrayList<>();
    private static boolean cleanupThreadStarted = false;

    static {
        startCleanupThread();
    }

    private static synchronized void startCleanupThread() {
        if (cleanupThreadStarted) return;
        cleanupThreadStarted = true;
        Thread cleanup = new Thread(() -> {
            while (true) {
                try {
                    SectionPhantomRef ref = (SectionPhantomRef) refQueue.remove(1000);
                    if (ref != null) {
                        synchronized (phantomRefs) {
                            phantomRefs.remove(ref);
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "Endless-SectionPool-Cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    public static synchronized LevelChunkSection checkout() {
        if (!pool.isEmpty()) {
            return pool.poll();
        }
        return null;
    }

    public static synchronized void reclaimSection(LevelChunkSection section) {
        if (section == null) return;
        if (!section.hasOnlyAir()) return;
        if (pool.size() >= MAX_POOLED_SECTIONS) return;

        SectionPhantomRef ref = new SectionPhantomRef(section, refQueue);
        synchronized (phantomRefs) {
            phantomRefs.add(ref);
        }
        pool.offer(section);
    }

    public static int getPoolSize() {
        return pool.size();
    }

    private static class SectionPhantomRef extends PhantomReference<LevelChunkSection> {
        SectionPhantomRef(LevelChunkSection referent, ReferenceQueue<? super LevelChunkSection> q) {
            super(referent, q);
        }
    }
}
