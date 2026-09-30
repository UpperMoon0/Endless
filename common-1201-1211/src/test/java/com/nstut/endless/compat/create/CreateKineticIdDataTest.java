package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CreateKineticIdDataTest {
    @Test void syntheticIdsAreOutsideEveryLegalHorizontalPosition() {
        Set<Long> ids = new HashSet<>();
        for (long sequence = 0; sequence < 8192; sequence++) {
            long id = CreateKineticIdData.syntheticIdForSequence(sequence);
            assertEquals(30_000_000, BlockPos.of(id).getX());
            assertTrue(CreateKineticIdData.isSyntheticId(id));
            assertTrue(ids.add(id));
        }
        assertEquals(30_000_000, BlockPos.of(CreateKineticIdData.syntheticIdForSequence((1L << 38) - 1)).getX());
        assertThrows(IllegalArgumentException.class, () -> CreateKineticIdData.syntheticIdForSequence(1L << 38));
        assertThrows(IllegalArgumentException.class, () -> CreateKineticIdData.syntheticIdForSequence(-1));
    }

    @Test void sparseLegacyPositionsAndHorizontalBoundariesCannotBeSynthetic() {
        for (int x : new int[]{-30_000_000, -29_999_999, -1, 0, 29_999_999})
            for (int z : new int[]{-30_000_000, 0, 29_999_999})
                for (int y : new int[]{-8_000_000, -2048, 2032, 2048, 2063, 7_999_999})
                    assertFalse(CreateKineticIdData.isSyntheticId(new BlockPos(x, y, z).asLong()));
        assertEquals(2032, new BlockPos(0, 2032, 0).asLong());
        assertNotEquals(2032, CreateKineticIdData.syntheticIdForSequence(0));
    }
}
