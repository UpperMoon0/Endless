package com.nstut.endless.vertical;

/**
 * Sparse vertical-page address. X/Z retain vanilla chunk coordinates while
 * pageY is a full signed int independent from vanilla packed section keys.
 */
public record VerticalPagePos(int chunkX, int pageY, int chunkZ) {

    public static final int CHUNK_WIDTH = 16;

    public static VerticalPagePos fromBlock(int blockX, int blockY, int blockZ) {
        return new VerticalPagePos(
                Math.floorDiv(blockX, CHUNK_WIDTH),
                VerticalPageLayout.pageYForBlockY(blockY),
                Math.floorDiv(blockZ, CHUNK_WIDTH));
    }

    public static VerticalPagePos fromChunkAndSection(int chunkX, int sectionY, int chunkZ) {
        return new VerticalPagePos(chunkX, VerticalPageLayout.pageYForSectionY(sectionY), chunkZ);
    }

    public int minBlockY() {
        return VerticalPageLayout.pageMinBlockY(pageY);
    }

    public int maxBlockY() {
        return VerticalPageLayout.pageMaxBlockY(pageY);
    }
}
