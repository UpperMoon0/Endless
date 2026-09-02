package com.nstut.endless.vertical;

/**
 * Coordinate math for the sparse vertical-page engine.
 *
 * <p>A page deliberately stays small enough to reuse vanilla-sized section
 * storage internally. Absolute Y never needs to be packed into vanilla's
 * 12-bit {@code BlockPos} long representation just to locate a page.</p>
 */
public final class VerticalPageLayout {

    public static final int SECTION_HEIGHT = 16;
    public static final int SECTIONS_PER_PAGE = 32;
    public static final int BLOCKS_PER_PAGE = SECTION_HEIGHT * SECTIONS_PER_PAGE;

    private VerticalPageLayout() {
    }

    public static int pageYForBlockY(int blockY) {
        return Math.floorDiv(blockY, BLOCKS_PER_PAGE);
    }

    public static int localBlockY(int blockY) {
        return Math.floorMod(blockY, BLOCKS_PER_PAGE);
    }

    public static int sectionYForBlockY(int blockY) {
        return Math.floorDiv(blockY, SECTION_HEIGHT);
    }

    public static int pageYForSectionY(int sectionY) {
        return Math.floorDiv(sectionY, SECTIONS_PER_PAGE);
    }

    public static int localSectionY(int sectionY) {
        return Math.floorMod(sectionY, SECTIONS_PER_PAGE);
    }

    public static int blockY(int pageY, int localBlockY) {
        checkLocalBlockY(localBlockY);
        return Math.toIntExact((long) pageY * BLOCKS_PER_PAGE + localBlockY);
    }

    public static int sectionY(int pageY, int localSectionY) {
        checkLocalSectionY(localSectionY);
        return Math.toIntExact((long) pageY * SECTIONS_PER_PAGE + localSectionY);
    }

    public static int pageMinBlockY(int pageY) {
        return blockY(pageY, 0);
    }

    public static int pageMaxBlockY(int pageY) {
        return blockY(pageY, BLOCKS_PER_PAGE - 1);
    }

    public static void checkLocalBlockY(int localBlockY) {
        if (localBlockY < 0 || localBlockY >= BLOCKS_PER_PAGE) {
            throw new IndexOutOfBoundsException(
                    "local block Y must be in [0, " + BLOCKS_PER_PAGE + "): " + localBlockY);
        }
    }

    public static void checkLocalSectionY(int localSectionY) {
        if (localSectionY < 0 || localSectionY >= SECTIONS_PER_PAGE) {
            throw new IndexOutOfBoundsException(
                    "local section Y must be in [0, " + SECTIONS_PER_PAGE + "): " + localSectionY);
        }
    }
}
