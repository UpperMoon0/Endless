package com.nstut.endless.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Version-specific vanilla NBT API used by the real Create migration regression. */
final class LiveCreateNbt {
    private LiveCreateNbt() {}
    static CompoundTag save(ServerLevel level, BlockEntity entity) { return entity.saveWithFullMetadata(); }
    static BlockEntity load(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        return BlockEntity.loadStatic(pos, state, tag);
    }
}
