package com.nstut.endless.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Version-specific NBT APIs used by the real Create migration/serialization regressions. */
final class LiveCreateNbt {
    private LiveCreateNbt() {}
    static CompoundTag save(ServerLevel level, BlockEntity entity) { return entity.saveWithFullMetadata(level.registryAccess()); }
    static BlockEntity load(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        return BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
    }
    static CompoundTag writeContraption(ServerLevel level, Object contraption) throws ReflectiveOperationException {
        // Create 6.0.11 uses the 1.21.1 registry-aware writer, unlike 6.0.8.
        return (CompoundTag) contraption.getClass()
            .getMethod("writeNBT", HolderLookup.Provider.class, boolean.class)
            .invoke(contraption, level.registryAccess(), false);
    }

}
