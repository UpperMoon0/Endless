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
    static void prepareGameTestTemplate(ServerLevel level, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) {
        // Pinned NeoForge Create templates declare current DataVersion but retain
        // legacy Count bytes in modded BE inventories, which vanilla's structure
        // fixer cannot visit. Preserve those exact input counts in the current codec.
        CompoundTag tag = template.save(new CompoundTag());
        int changed = normalizeFixtureCounts(tag) + LiveCreateGameTests.normalizeLegacyThresholds(tag);
        if (changed > 0) {
            template.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            System.out.println("ENDLESS_CREATE_GAMETEST_TEMPLATE_COUNTS_NORMALIZED stacks=" + changed);
        }
    }
    private static int normalizeFixtureCounts(net.minecraft.nbt.Tag tag) {
        int changed = 0;
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", 8) && compound.contains("Count", 99) && !compound.contains("count", 99)) {
                compound.putInt("count", compound.getInt("Count")); compound.remove("Count"); changed++;
            }
            for (String key : java.util.List.copyOf(compound.getAllKeys())) changed += normalizeFixtureCounts(compound.get(key));
        } else if (tag instanceof net.minecraft.nbt.ListTag list) for (net.minecraft.nbt.Tag entry : list) changed += normalizeFixtureCounts(entry);
        return changed;
    }
    static BlockPos readPos(CompoundTag tag, String key) { return net.minecraft.nbt.NbtUtils.readBlockPos(tag, key).orElseThrow(); }
    @SuppressWarnings("unchecked")
    static boolean symmetryEnabled(net.minecraft.world.item.ItemStack stack) throws ReflectiveOperationException {
        var type = (net.minecraft.core.component.DataComponentType<Boolean>) Class.forName("com.simibubi.create.AllDataComponents").getField("SYMMETRY_WAND_ENABLE").get(null);
        return stack.getOrDefault(type, false);
    }
    @SuppressWarnings("unchecked")
    static void setBacktankAir(net.minecraft.world.item.ItemStack stack, int air) throws ReflectiveOperationException {
        var type = (net.minecraft.core.component.DataComponentType<Integer>) Class.forName("com.simibubi.create.AllDataComponents").getField("BACKTANK_AIR").get(null);
        stack.set(type, air);
    }
    static void seedRecipe(ServerLevel level) { level.random.setSeed(0L); }
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
