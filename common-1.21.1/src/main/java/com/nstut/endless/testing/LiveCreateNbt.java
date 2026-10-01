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
        return writeContraption(level, contraption, false);
    }
    static CompoundTag writeContraption(ServerLevel level, Object contraption, boolean spawn) throws ReflectiveOperationException {
        return (CompoundTag) contraption.getClass().getMethod("writeNBT", HolderLookup.Provider.class, boolean.class)
            .invoke(contraption, level.registryAccess(), spawn);
    }

    static void writeBehaviour(ServerLevel level, Object behaviour, CompoundTag tag) throws ReflectiveOperationException {
        behaviour.getClass().getMethod("write", CompoundTag.class, HolderLookup.Provider.class, boolean.class)
            .invoke(behaviour, tag, level.registryAccess(), false);
    }
    static void readBehaviour(ServerLevel level, Object behaviour, CompoundTag tag) throws ReflectiveOperationException {
        behaviour.getClass().getMethod("read", CompoundTag.class, HolderLookup.Provider.class, boolean.class)
            .invoke(behaviour, tag, level.registryAccess(), false);
    }

    static void writeAssemblyException(ServerLevel level, CompoundTag tag, Object exception) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.simibubi.create.content.contraptions.AssemblyException");
        type.getMethod("write", CompoundTag.class, HolderLookup.Provider.class, type).invoke(null, tag, level.registryAccess(), exception);
    }
    static Object readAssemblyException(ServerLevel level, CompoundTag tag) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.simibubi.create.content.contraptions.AssemblyException");
        return type.getMethod("read", CompoundTag.class, HolderLookup.Provider.class).invoke(null, tag, level.registryAccess());
    }
}
