package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateKineticIdDataTest {

    @Test
    void syntheticIdsUseOnlyDenseCorePackedYGapAndStayUnique() {
        Set<Long> ids = new HashSet<>();
        long yMask = (1L << BlockPos.PACKED_Y_LENGTH) - 1L;

        for (long sequence = 0; sequence < 4096; sequence++) {
            long id = CreateKineticIdData.syntheticIdForSequence(sequence);
            long yCode = id & yMask;
            assertTrue(yCode >= 2032 && yCode < 2064);
            assertTrue(ids.add(id), "synthetic ID repeated for sequence " + sequence);
        }
    }

    @Test
    void firstReservedCycleCarriesSequenceInYSelector() {
        long yMask = (1L << BlockPos.PACKED_Y_LENGTH) - 1L;
        for (long sequence = 0; sequence < 32; sequence++) {
            assertEquals(2032 + sequence, CreateKineticIdData.syntheticIdForSequence(sequence) & yMask);
        }
    }
}