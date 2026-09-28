package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateFullPositionTest {

    @Test
    void exactTagRoundTripsBeyondPackedBlockPosY() {
        CompoundTag tag = new CompoundTag();
        BlockPos source = new BlockPos(-12345, 7_999_999, 54321);

        CreateFullPosition.put(tag, "Pos", source);

        assertTrue(CreateFullPosition.contains(tag, "Pos"));
        assertEquals(source, CreateFullPosition.get(tag, "Pos"));
    }

    @Test
    void displayReservationKeysAreLineSpecific() {
        assertEquals("EndlessLine3Position", CreateFullPosition.displayLineKey(3));
    }
}