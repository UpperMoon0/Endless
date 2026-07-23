package com.nstut.endless.storage;

import com.nstut.endless.config.EndlessConfig;

public final class ColumnRleStorage {

    public static final long AIR_POINT = packPoint(0, 0, 0);

    private final long[] points;
    private final int count;

    public ColumnRleStorage(long[] points, int count) {
        this.points = points;
        this.count = count;
    }

    public static long packPoint(int blockId, int height, int minY) {
        return ((long) blockId << 44) | ((long) (height & 0xFFF) << 32) | ((long) (minY & 0xFFF) << 20);
    }

    public static int unpackBlockId(long point) {
        return (int) (point >>> 44);
    }

    public static int unpackHeight(long point) {
        return (int) ((point >>> 32) & 0xFFF);
    }

    public static int unpackMinY(long point) {
        int raw = (int) ((point >>> 20) & 0xFFF);
        return (raw << 20) >> 20;
    }

    public boolean isEmpty() {
        return count == 0 || (count == 1 && unpackBlockId(points[0]) == 0);
    }

    public int getPointCount() {
        return count;
    }

    public long getPoint(int index) {
        return points[index];
    }

    public int getHeightAt(int targetBlockId) {
        for (int i = count - 1; i >= 0; i--) {
            if (unpackBlockId(points[i]) == targetBlockId) {
                return unpackMinY(points[i]) + unpackHeight(points[i]);
            }
        }
        return EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
    }

    public static ColumnRleStorage fromPacked(long[] packed, int count) {
        return new ColumnRleStorage(packed, count);
    }

    public long[] getPackedData() {
        return points;
    }
}
