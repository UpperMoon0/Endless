package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Versioned optional extension to Create's paletted block entry; old Pos longs remain readable. */
public final class CreateContraptionPosition {
    public static final String KEY = "EndlessLocalPosition";
    private CreateContraptionPosition() {}
    public static void write(CompoundTag entry, BlockPos position) {
        CreateFullPosition.put(entry, KEY, position);
        entry.getCompound(KEY).putInt("Version", 1);
    }
    public static BlockPos read(CompoundTag entry, long packed) {
        if (!entry.contains(KEY)) return BlockPos.of(packed);
        if (!entry.contains(KEY, 10)) throw new IllegalArgumentException("Invalid exact contraption position");
        CompoundTag position = entry.getCompound(KEY);
        if (!position.contains("Version", 3) || position.getInt("Version") != 1
            || !position.contains("X", 3) || !position.contains("Y", 3) || !position.contains("Z", 3))
            throw new IllegalArgumentException("Unsupported or incomplete exact contraption position");
        return CreateFullPosition.get(entry, KEY);
    }
}
