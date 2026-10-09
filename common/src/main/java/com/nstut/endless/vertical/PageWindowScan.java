package com.nstut.endless.vertical;

import java.util.LinkedHashMap;
import java.util.function.BiPredicate;

/** Near-first discovery with resumable page admission and deduplicated chunk notifications. */
public final class PageWindowScan {
    private record Chunk(int x, int z) {}

    private final int centerX, centerZ, viewDistance, centerPageY, pageRadius;
    private final LinkedHashMap<Chunk, Integer> notifications = new LinkedHashMap<>();
    private int cursor;
    private int nextPage;

    public PageWindowScan(int centerX, int centerZ, int viewDistance, int centerPageY, int pageRadius) {
        if (viewDistance < 0 || pageRadius < 0) throw new IllegalArgumentException("Negative radius");
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.viewDistance = viewDistance;
        this.centerPageY = centerPageY;
        this.pageRadius = pageRadius;
    }

    /** A repeat notification must not rewind a partially admitted chunk. */
    public void notifyChunk(int x, int z) {
        notifications.putIfAbsent(new Chunk(x, z), 0);
    }

    public void discardNotificationsOutside(int x, int z, int radius) {
        notifications.keySet().removeIf(chunk -> Math.abs((long) chunk.x - x) > radius
            || Math.abs((long) chunk.z - z) > radius);
    }

    /**
     * Inspect at most maxChunks loaded-chunk candidates. A rejected page leaves its exact
     * cursor pending, including notifications received after the initial scan completed.
     * No native chunk loads or page-existence probes are performed by this class.
     */
    public void advance(int maxChunks, BiPredicate<Integer, Integer> loaded, PageOffer offer) {
        int total = (2 * viewDistance + 1) * (2 * viewDistance + 1);
        for (int n = 0; n < maxChunks; n++) {
            boolean notified = !notifications.isEmpty();
            if (!notified && cursor >= total) return;
            Chunk chunk = notified ? notifications.keySet().iterator().next() : chunkAt(cursor);
            int page = notified ? notifications.get(chunk) : nextPage;
            if (loaded.test(chunk.x, chunk.z)) {
                for (; page <= 2 * pageRadius; page++) {
                    if (!offer.offer(chunk.x, centerPageY - pageRadius + page, chunk.z)) {
                        if (notified) notifications.put(chunk, page);
                        else nextPage = page;
                        return;
                    }
                }
            }
            if (notified) notifications.remove(chunk);
            else {
                cursor++;
                nextPage = 0;
            }
        }
    }

    public boolean isComplete() {
        return cursor >= (2 * viewDistance + 1) * (2 * viewDistance + 1) && notifications.isEmpty();
    }

    private Chunk chunkAt(int index) {
        if (index == 0) return new Chunk(centerX, centerZ);
        int remaining = index - 1;
        for (int r = 1; r <= viewDistance; r++) {
            int edge = 2 * r, perimeter = 4 * edge;
            if (remaining >= perimeter) { remaining -= perimeter; continue; }
            int dx, dz;
            if (remaining < edge) { dx = -r + remaining; dz = -r; }
            else if (remaining < 2 * edge) { dx = r; dz = -r + remaining - edge; }
            else if (remaining < 3 * edge) { dx = r - (remaining - 2 * edge); dz = r; }
            else { dx = -r; dz = r - (remaining - 3 * edge); }
            return new Chunk(centerX + dx, centerZ + dz);
        }
        throw new IllegalStateException("Window scan cursor outside view");
    }

    @FunctionalInterface
    public interface PageOffer {
        /** True if admitted (or intentionally skipped), false if capacity requires a retry. */
        boolean offer(int chunkX, int pageY, int chunkZ);
    }
}
