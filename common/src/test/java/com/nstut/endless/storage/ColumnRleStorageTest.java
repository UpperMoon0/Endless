package com.nstut.endless.storage;

import org.junit.jupiter.api.Test;

import static com.nstut.endless.storage.ColumnRleStorage.*;
import static org.junit.jupiter.api.Assertions.*;

class ColumnRleStorageTest {

    @Test
    void packAndUnpack_roundTrip() {
        long point = packPoint(42, 547, -64);
        assertEquals(42, unpackBlockId(point));
        assertEquals(547, unpackHeight(point));
        assertEquals(-64, unpackMinY(point));
    }

    @Test
    void packAndUnpack_maxValues() {
        long point = packPoint(0xFFFFF, 0xFFF, 0x7FF);
        assertEquals(0xFFFFF, unpackBlockId(point));
        assertEquals(0xFFF, unpackHeight(point));
        assertEquals(0x7FF, unpackMinY(point));
    }

    @Test
    void packAndUnpack_negativeMinY() {
        long point = packPoint(1, 64, -64);
        assertEquals(1, unpackBlockId(point));
        assertEquals(64, unpackHeight(point));
        assertEquals(-64, unpackMinY(point));
    }

    @Test
    void airPoint_isActuallyAir() {
        assertEquals(0, unpackBlockId(AIR_POINT));
        assertEquals(0, unpackHeight(AIR_POINT));
        assertEquals(0, unpackMinY(AIR_POINT));
    }

    @Test
    void emptyStorage_isEmpty() {
        ColumnRleStorage storage = fromPacked(new long[]{AIR_POINT}, 1);
        assertTrue(storage.isEmpty());
    }

    @Test
    void singleRun_isNotEmpty() {
        long point = packPoint(1, 10, 0);
        ColumnRleStorage storage = fromPacked(new long[]{point}, 1);
        assertFalse(storage.isEmpty());
    }

    @Test
    void emptyCountZero_isEmpty() {
        ColumnRleStorage storage = fromPacked(new long[0], 0);
        assertTrue(storage.isEmpty());
    }

    @Test
    void tallAirColumn_compressesToFewPoints() {
        long[] points = {packPoint(0, 547, -64)};
        ColumnRleStorage storage = fromPacked(points, 1);
        assertEquals(1, storage.getPointCount());
        assertTrue(storage.isEmpty());

        assertEquals(-64, storage.getHeightAt(1),
            "Block 1 not present, should return minBuildHeight");
    }

    @Test
    void getHeightAt_matchingBlock() {
        long[] points = {
            packPoint(0, 50, -64),
            packPoint(7, 100, -14),
            packPoint(0, 64, 86),
            packPoint(7, 50, 150)
        };
        ColumnRleStorage storage = fromPacked(points, 4);
        assertEquals(200, storage.getHeightAt(7),
            "Topmost run of block 7 ends at 150+50=200");
    }

    @Test
    void getHeightAt_noMatchingBlock_returnsDefault() {
        long[] points = {packPoint(7, 100, 0)};
        ColumnRleStorage storage = fromPacked(points, 1);
        assertEquals(
            com.nstut.endless.config.EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight(),
            storage.getHeightAt(999)
        );
    }

    @Test
    void packedDataPreservesOrder() {
        long p1 = packPoint(1, 10, 0);
        long p2 = packPoint(2, 20, 10);
        ColumnRleStorage storage = fromPacked(new long[]{p1, p2}, 2);
        assertEquals(p1, storage.getPoint(0));
        assertEquals(p2, storage.getPoint(1));
    }
}
