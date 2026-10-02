package com.nstut.endless.testing;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** Validate the actual native transform after controller snapping, wherever the machine travelled. */
final class LiveCreateDisassemblyTest {
    private LiveCreateDisassemblyTest() {}
    @SuppressWarnings("unchecked")
    static void verify(ServerLevel level, Entity entity, int slimeBlocks) throws ReflectiveOperationException {
        Object transform = declared(entity, "makeStructureTransform");
        Object contraption = entity.getClass().getMethod("getContraption").invoke(entity);
        Map<BlockPos, StructureBlockInfo> blocks = (Map<BlockPos, StructureBlockInfo>) contraption.getClass().getMethod("getBlocks").invoke(contraption);
        var drill = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:mechanical_drill"));
        Map<BlockPos, BlockState> expected = new HashMap<>();
        int chestCount = 0, diamonds = 0, drills = 0, slime = 0;
        for (StructureBlockInfo info : blocks.values()) {
            if (!info.state().is(Blocks.SLIME_BLOCK) && !info.state().is(Blocks.CHEST) && !info.state().is(drill)) continue;
            BlockPos pos = (BlockPos) transform.getClass().getMethod("apply", BlockPos.class).invoke(transform, info.pos());
            BlockState state = (BlockState) transform.getClass().getMethod("apply", BlockState.class).invoke(transform, info.state());
            require(expected.put(pos, state) == null, "native disassembly aliased payload positions");
            require(level.getBlockState(pos).equals(state), "restored disassembly changed exact block/state at " + pos + " expected=" + state + " actual=" + level.getBlockState(pos));
            if (state.is(Blocks.SLIME_BLOCK)) slime++;
            if (state.is(drill)) drills++;
            if (state.is(Blocks.CHEST)) {
                require(level.getBlockEntity(pos) instanceof ChestBlockEntity, "restored chest block entity missing at " + pos);
                ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(pos); chestCount++;
                for (int slot = 0; slot < chest.getContainerSize(); slot++) if (chest.getItem(slot).is(Items.DIAMOND)) diamonds += chest.getItem(slot).getCount();
            }
        }
        require(slime == slimeBlocks && chestCount == 1 && drills == 1 && diamonds == 7,
            "restored disassembly lost/duplicated payload: slime=" + slime + " chest=" + chestCount + " drill=" + drills + " diamonds=" + diamonds);
        // Also reject duplicate payloads beside the exact native placement cells.
        java.util.Set<BlockPos> neighbours = new java.util.HashSet<>();
        for (BlockPos pos : expected.keySet()) BlockPos.betweenClosedStream(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)).forEach(p -> neighbours.add(p.immutable()));
        for (BlockPos pos : neighbours) if (!expected.containsKey(pos)) {
            BlockState state = level.getBlockState(pos);
            require(!state.is(Blocks.SLIME_BLOCK) && !state.is(Blocks.CHEST) && !state.is(drill), "restored disassembly duplicated a payload beside its native target: " + pos);
        }
    }
    private static Object declared(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try {
            Method method = type.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
        } catch (NoSuchMethodException ignored) {}
        throw new NoSuchMethodException(name);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
