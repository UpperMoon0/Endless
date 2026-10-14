package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnHeightCacheTest {
    @Test void packedChunkGridKeepsEveryFullCoordinateDistinct() {
        var cache = new ColumnHeightCache(6);
        for (int x = -16; x < 16; x++) for (int z = -16; z < 16; z++) {
            long chunk = (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
            cache.put(chunk, 0, 0, (x + 16) * 32 + z + 16);
        }
        cache.invalidateChunk(0);
        for (int x = -16; x < 16; x++) for (int z = -16; z < 16; z++) {
            long chunk = (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
            assertEquals(x == 0 && z == 0 ? ColumnHeightCache.UNKNOWN : (x + 16) * 32 + z + 16,
                cache.get(chunk, 0, 0));
        }
    }
    @Test void editsInvalidateEveryTypeOnlyInTheAffectedColumn() {
        var cache = new ColumnHeightCache(6);
        for (long chunk : new long[]{-1L, 0L, Long.MAX_VALUE})
            for (int local = 0; local < 256; local++)
                for (int type = 0; type < 6; type++) cache.put(chunk, local, type, local + type);
        cache.invalidate(-1L, 255);
        for (int type = 0; type < 6; type++) {
            assertEquals(ColumnHeightCache.UNKNOWN, cache.get(-1L, 255, type));
            assertEquals(254 + type, cache.get(-1L, 254, type));
            assertEquals(255 + type, cache.get(0L, 255, type));
            assertEquals(255 + type, cache.get(Long.MAX_VALUE, 255, type));
        }
    }

    @Test void emptyResultsExtremeHeightsAndChunkLifecycleRemainDistinct() {
        var cache = new ColumnHeightCache(6);
        assertEquals(ColumnHeightCache.UNKNOWN, cache.get(0L, 0, 0));
        cache.put(0L, 0, 0, Integer.MIN_VALUE);
        cache.put(0L, 0, 1, -8_000_000);
        cache.put(1L, 255, 5, 8_000_000);
        assertEquals(Integer.MIN_VALUE, cache.get(0L, 0, 0));
        assertEquals(-8_000_000, cache.get(0L, 0, 1));
        cache.invalidateChunk(0L);
        assertEquals(ColumnHeightCache.UNKNOWN, cache.get(0L, 0, 1));
        assertEquals(8_000_000, cache.get(1L, 255, 5));
        cache.put(0L, 0, 0, 123);
        assertEquals(123, cache.get(0L, 0, 0));
        cache.clear();
        assertEquals(ColumnHeightCache.UNKNOWN, cache.get(1L, 255, 5));
    }
}
