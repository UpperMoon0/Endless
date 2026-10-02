package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateContraptionPositionTest {
    @Test void nativeLegacyKeysRemainReadable() {
        BlockPos position = new BlockPos(1, -2048, 3);
        assertEquals(position, CreateContraptionPosition.read(new CompoundTag(), position.asLong()));
    }
    @Test void exactCoordinatesKeepCollidingLocalKeysSeparate() {
        for (int y : new int[]{-8_000_000, -4096, -2049, -2048, 2047, 2048, 4096, 7_999_999}) {
            BlockPos position = new BlockPos(5, y, -7);
            CompoundTag entry = new CompoundTag();
            CreateContraptionPosition.write(entry, position);
            assertEquals(position, CreateContraptionPosition.read(entry.copy(), position.asLong()));
        }
        BlockPos a = new BlockPos(0, 2048, 0), b = new BlockPos(0, -2048, 0);
        assertEquals(a.asLong(), b.asLong());
        CompoundTag first = new CompoundTag(), second = new CompoundTag();
        CreateContraptionPosition.write(first, a); CreateContraptionPosition.write(second, b);
        assertNotEquals(CreateContraptionPosition.read(first, a.asLong()), CreateContraptionPosition.read(second, b.asLong()));
    }
    @Test void malformedExactCoordinatesCannotSilentlyFallBackToWrappedKey() {
        CompoundTag entry = new CompoundTag();
        CreateContraptionPosition.write(entry, new BlockPos(0, 2048, 0));
        entry.getCompound(CreateContraptionPosition.KEY).remove("Y");
        assertThrows(IllegalArgumentException.class, () -> CreateContraptionPosition.read(entry, 2048));
        entry.putString(CreateContraptionPosition.KEY, "invalid");
        assertThrows(IllegalArgumentException.class, () -> CreateContraptionPosition.read(entry, 2048));
    }
    @Test void unknownSchemaIsRefused() {
        CompoundTag entry = new CompoundTag(); CreateContraptionPosition.write(entry, BlockPos.ZERO);
        entry.getCompound(CreateContraptionPosition.KEY).putInt("Version", 2);
        assertThrows(IllegalArgumentException.class, () -> CreateContraptionPosition.read(entry, 0));
    }
}
