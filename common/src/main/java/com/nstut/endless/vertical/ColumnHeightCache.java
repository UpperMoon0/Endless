package com.nstut.endless.vertical;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Sparse-height results indexed by chunk and local X/Z, including cached empty columns.
 * The owning world supplies synchronization. Heights fit inside Endless's +/-8M envelope;
 * MIN_VALUE remains the valid "no matching sparse block" result.
 */
public final class ColumnHeightCache {
    public static final int UNKNOWN = Integer.MIN_VALUE + 1;
    private final int types;
    private final Map<Long, int[]> chunks = new HashMap<>();

    public ColumnHeightCache(int types) {
        if (types < 1) throw new IllegalArgumentException("Positive height type count required");
        this.types = types;
    }

    public int get(long chunk, int local, int type) {
        int[] heights = chunks.get(cacheKey(chunk));
        return heights == null ? UNKNOWN : heights[local * types + type];
    }

    public void put(long chunk, int local, int type, int height) {
        int[] heights = chunks.computeIfAbsent(cacheKey(chunk), ignored -> {
            int[] values = new int[256 * types];
            Arrays.fill(values, UNKNOWN);
            return values;
        });
        heights[local * types + type] = height;
    }

    public void invalidate(long chunk, int local) {
        int[] heights = chunks.get(cacheKey(chunk));
        if (heights != null) Arrays.fill(heights, local * types, (local + 1) * types, UNKNOWN);
    }

    public void invalidateChunk(long chunk) { chunks.remove(cacheKey(chunk)); }
    public void clear() { chunks.clear(); }

    // Long.hashCode folds packed ChunkPos to x ^ z, clustering square view
    // windows into tree bins. This bijection preserves full coordinate identity
    // while distributing both halves before HashMap's 32-bit hash fold.
    private static long cacheKey(long chunk) {
        long mixed = (chunk ^ (chunk >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }
}
