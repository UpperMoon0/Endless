package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateDestructionPositionsTest {
    @Test void aliasesAndMutableInputsRemainExactAndReleaseTheirKeys() {
        CreateDestructionPositions keys = new CreateDestructionPositions();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(7, 1_000_000, 9);
        BlockPos first = mutable.immutable(), alias = first.above(4096);
        assertEquals(first.asLong(), alias.asLong());
        long a = keys.key(mutable), b = keys.key(alias);
        mutable.setY(-1_000_000);
        assertNotEquals(a, b);
        assertEquals(first, keys.position(a));
        assertEquals(alias, keys.position(b));
        assertEquals(a, keys.lookup(first));
        assertEquals(Long.MIN_VALUE, keys.lookup(mutable));
        assertEquals(2, keys.size());
        keys.remove(first);
        assertEquals(Long.MIN_VALUE, keys.lookup(first));
        assertEquals(alias, keys.position(b));
        keys.clear();
        assertEquals(0, keys.size());
        assertEquals(Long.MIN_VALUE, keys.lookup(alias));
    }
}
