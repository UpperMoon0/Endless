package com.nstut.endless.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerticalPageEngineTest {

    @Test
    void blockCoordinatesUseFloorDivisionAcrossZero() {
        assertEquals(0, VerticalPageLayout.pageYForBlockY(0));
        assertEquals(0, VerticalPageLayout.localBlockY(0));
        assertEquals(0, VerticalPageLayout.pageYForBlockY(511));
        assertEquals(511, VerticalPageLayout.localBlockY(511));
        assertEquals(1, VerticalPageLayout.pageYForBlockY(512));
        assertEquals(0, VerticalPageLayout.localBlockY(512));

        assertEquals(-1, VerticalPageLayout.pageYForBlockY(-1));
        assertEquals(511, VerticalPageLayout.localBlockY(-1));
        assertEquals(-1, VerticalPageLayout.pageYForBlockY(-512));
        assertEquals(0, VerticalPageLayout.localBlockY(-512));
        assertEquals(-2, VerticalPageLayout.pageYForBlockY(-513));
        assertEquals(511, VerticalPageLayout.localBlockY(-513));
    }

    @Test
    void everySignedIntBlockYRoundTripsThroughPageCoordinates() {
        int[] samples = {
                Integer.MIN_VALUE,
                Integer.MIN_VALUE + 1,
                -1_000_000,
                -513,
                -512,
                -1,
                0,
                1,
                511,
                512,
                1_000_000,
                Integer.MAX_VALUE - 1,
                Integer.MAX_VALUE
        };

        for (int blockY : samples) {
            int pageY = VerticalPageLayout.pageYForBlockY(blockY);
            int localY = VerticalPageLayout.localBlockY(blockY);
            assertEquals(blockY, VerticalPageLayout.blockY(pageY, localY));
        }
    }

    @Test
    void sectionCoordinatesUseSameNegativeBoundaryRules() {
        assertEquals(0, VerticalPageLayout.pageYForSectionY(31));
        assertEquals(31, VerticalPageLayout.localSectionY(31));
        assertEquals(1, VerticalPageLayout.pageYForSectionY(32));
        assertEquals(0, VerticalPageLayout.localSectionY(32));
        assertEquals(-1, VerticalPageLayout.pageYForSectionY(-1));
        assertEquals(31, VerticalPageLayout.localSectionY(-1));
        assertEquals(-1, VerticalPageLayout.pageYForSectionY(-32));
        assertEquals(0, VerticalPageLayout.localSectionY(-32));
        assertEquals(-2, VerticalPageLayout.pageYForSectionY(-33));
        assertEquals(31, VerticalPageLayout.localSectionY(-33));
    }

    @Test
    void pagePositionKeepsHorizontalChunkingIndependentFromAbsoluteY() {
        VerticalPagePos pos = VerticalPagePos.fromBlock(-1, 1_000_000, -17);

        assertEquals(-1, pos.chunkX());
        assertEquals(1953, pos.pageY());
        assertEquals(-2, pos.chunkZ());
        assertTrue(1_000_000 >= pos.minBlockY());
        assertTrue(1_000_000 <= pos.maxBlockY());
    }

    @Test
    void verticalPageTracksOnlyOccupiedSections() {
        VerticalPage<String> page = new VerticalPage<>(3);
        int firstSection = VerticalPageLayout.sectionY(3, 0);
        int lastSection = VerticalPageLayout.sectionY(3, 31);

        assertTrue(page.isEmpty());
        assertNull(page.putSection(firstSection, "bottom"));
        assertNull(page.putSection(lastSection, "top"));
        assertEquals(2, page.occupiedSectionCount());
        assertEquals("bottom", page.getSection(firstSection));
        assertEquals("top", page.getSection(lastSection));

        assertEquals("bottom", page.putSection(firstSection, "replacement"));
        assertEquals(2, page.occupiedSectionCount());
        assertEquals("replacement", page.removeSection(firstSection));
        assertEquals(1, page.occupiedSectionCount());
        assertFalse(page.isEmpty());
        assertEquals("top", page.removeSection(lastSection));
        assertTrue(page.isEmpty());
    }

    @Test
    void verticalPageRejectsSectionsOwnedByAnotherPage() {
        VerticalPage<String> page = new VerticalPage<>(0);

        assertThrows(IllegalArgumentException.class, () -> page.putSection(32, "wrong page"));
        assertThrows(IllegalArgumentException.class, () -> page.getSection(-1));
    }

    @Test
    void sparseColumnAllocatesAndEvictsPagesOnDemand() {
        SparseVerticalColumn<String> column = new SparseVerticalColumn<>();

        column.putSection(-1, "below");
        column.putSection(0, "origin");
        column.putSection(32, "above");

        assertEquals(3, column.pageCount());
        assertEquals(List.of(-1, 0, 1), column.pageYs());
        assertEquals("below", column.getSection(-1));
        assertEquals("origin", column.getSection(0));
        assertEquals("above", column.getSection(32));

        assertEquals("origin", column.removeSection(0));
        assertEquals(2, column.pageCount());
        assertNull(column.getPage(0));
        assertEquals(List.of(-1, 1), column.pageYs());
    }

    @Test
    void occupiedIterationReportsAbsoluteSectionCoordinates() {
        VerticalPage<String> page = new VerticalPage<>(-2);
        page.putLocalSection(0, "a");
        page.putLocalSection(31, "b");

        List<Integer> sectionYs = new ArrayList<>();
        page.forEachOccupiedSection((sectionY, value) -> sectionYs.add(sectionY));

        assertEquals(List.of(-64, -33), sectionYs);
    }

    @Test
    void customPageFactoryMustReturnRequestedPage() {
        SparseVerticalColumn<String> column = new SparseVerticalColumn<>();
        VerticalPage<String> created = new VerticalPage<>(7);

        assertSame(created, column.getOrCreatePage(7, pageY -> created));
        assertThrows(
                IllegalArgumentException.class,
                () -> column.getOrCreatePage(8, pageY -> new VerticalPage<>(9)));
    }
}
