package com.nstut.endless.vertical;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SparseSectionIndexTest {
    @Test void unloadingDenseColumnsNeverTouchesSparseOrUnrelatedSections() {
        SparseSectionIndex index = new SparseSectionIndex();
        var storage = new HashMap<Long, String>();
        for (long n = 0; n < 100_000; n++) storage.put(n, "dense");
        index.add(7, Long.MIN_VALUE); storage.put(Long.MIN_VALUE, "sparse");
        for (long column = 100; column < 10_000; column++)
            assertTrue(index.remove(column).isEmpty());
        assertEquals(100_001, storage.size());
        for (long section : index.remove(7)) storage.remove(section);
        assertEquals(100_000, storage.size());
        assertTrue(index.sections(7).isEmpty());
    }

    @Test void reloadDeletionAndFarCoordinatesStayColumnLocal() {
        SparseSectionIndex index = new SparseSectionIndex();
        long column = Long.MIN_VALUE + 31, other = Long.MAX_VALUE;
        index.add(column, 123); index.add(column, 456); index.add(column, 123);
        index.add(other, -789);
        Set<Long> snapshot = index.sections(column);
        assertEquals(Set.of(123L, 456L), index.remove(column));
        assertEquals(Set.of(123L, 456L), snapshot);
        assertEquals(Set.of(-789L), index.sections(other));
        index.add(column, 456);
        assertEquals(Set.of(456L), index.sections(column));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }
}
