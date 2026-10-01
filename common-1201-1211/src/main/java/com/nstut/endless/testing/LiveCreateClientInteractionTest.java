package com.nstut.endless.testing;

import com.nstut.endless.compat.create.CreateLogicalGeometry;
import com.nstut.endless.heights.EndlessHeights;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Exercise real client arm initialization and native arm/ejector selection handlers. */
public final class LiveCreateClientInteractionTest {
    public static final String PASS = "ENDLESS_CREATE_CLIENT_INTERACTION_PASS";
    private static boolean done;
    private LiveCreateClientInteractionTest() {}

    public static void run(Minecraft mc) throws ReflectiveOperationException {
        if (done) return;
        ItemStack held = mc.player.getMainHandItem();
        HitResult hit = mc.hitResult;
        try {
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.tryParse("create:wrench"))));
            for (int y : new int[]{-65, 320, -513, 511, -2049, 2047, -1_000_448, 1_000_448}) {
                verifyArms(mc, y);
                verifyEjectors(mc, y);
            }
            require(!CreateLogicalGeometry.isAreaLoaded(mc.level, new BlockPos(1_000_000, 320, 1_000_000), 6),
                "arm loaded-area fix admitted unloaded chunks");
            require(!CreateLogicalGeometry.isAreaLoaded(mc.level,
                new BlockPos(7, EndlessHeights.getMinBuildHeight() - 16, 7), 6),
                "arm loaded-area fix admitted outside logical envelope");
            done = true;
            System.out.println(PASS + " armInputs=true nativeSelections=true alias4096=true ejectorTargets=true denseAndPageBoundaries=true");
        } finally {
            mc.player.setItemInHand(InteractionHand.MAIN_HAND, held);
            mc.hitResult = hit;
        }
    }

    private static void verifyArms(Minecraft mc, int y) throws ReflectiveOperationException {
        BlockPos a = new BlockPos(7, y, 7), b = a.above(4096);
        Class<?> pointType = Class.forName("com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint");
        Class<?> handler = Class.forName("com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointHandler");
        try {
            for (BlockPos pos : List.of(a, b)) {
                mc.level.setBlock(pos, block("mechanical_arm"), 18);
                BlockPos depot = pos.east().above();
                mc.level.setBlock(depot, block("depot"), 18);
                Object point = pointType.getMethod("create", Level.class, BlockPos.class, BlockState.class)
                    .invoke(null, mc.level, depot, mc.level.getBlockState(depot));
                require(point != null, "real depot interaction point missing");
                pointType.getMethod("cycleMode").invoke(point); // default DEPOSIT -> TAKE
                ListTag points = new ListTag();
                points.add((CompoundTag) pointType.getMethod("serialize", BlockPos.class).invoke(point, pos));
                BlockEntity arm = mc.level.getBlockEntity(pos);
                field(arm.getClass(), "interactionPointTag").set(arm, points);
                field(arm.getClass(), "updateInteractionPoints").setBoolean(arm, true);
                Method init = method(arm.getClass(), "initInteractionPoints"); init.invoke(arm);
                List<?> inputs = (List<?>) field(arm.getClass(), "inputs").get(arm);
                require(inputs.size() == 1 && depot.equals(pointType.getMethod("getPos").invoke(inputs.get(0))),
                    "arm rejected sparse client interaction point at " + pos);
                hover(mc, pos);
                handler.getMethod("tick").invoke(null);
                List<?> selection = (List<?>) field(handler, "currentSelection").get(null);
                require(selection.size() == 1 && depot.equals(pointType.getMethod("getPos").invoke(selection.get(0))),
                    "arm alias returned another arm's interaction point");
            }
        } finally {
            for (BlockPos pos : List.of(a, b)) {
                mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                mc.level.setBlock(pos.east().above(), Blocks.AIR.defaultBlockState(), 18);
            }
            field(handler, "lastBlockPos").setLong(null, -1);
            ((List<?>) field(handler, "currentSelection").get(null)).clear();
        }
    }

    private static void verifyEjectors(Minecraft mc, int y) throws ReflectiveOperationException {
        BlockPos a = new BlockPos(7, y, 11), b = a.above(4096);
        Class<?> handler = Class.forName("com.simibubi.create.content.logistics.depot.EjectorTargetHandler");
        try {
            int distance = 2;
            for (BlockPos pos : List.of(a, b)) {
                mc.level.setBlock(pos, block("weighted_ejector"), 18);
                Object ejector = mc.level.getBlockEntity(pos);
                ejector.getClass().getMethod("setTarget", int.class, int.class).invoke(ejector, distance++, 1);
                Object expected = ejector.getClass().getMethod("getTargetPosition").invoke(ejector);
                hover(mc, pos);
                handler.getMethod("tick").invoke(null);
                require(expected.equals(field(handler, "currentSelection").get(null)), "ejector packed alias retained stale target");
            }
            mc.hitResult = BlockHitResult.miss(Vec3.ZERO, Direction.UP, b);
            handler.getMethod("tick").invoke(null);
        } finally {
            for (BlockPos pos : List.of(a, b)) mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
            field(handler, "lastHoveredBlockPos").setLong(null, -1);
            field(handler, "currentSelection").set(null, null);
            field(handler, "currentItem").set(null, null);
        }
    }

    private static void hover(Minecraft mc, BlockPos pos) {
        mc.hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }
    private static BlockState block(String id) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + id)).defaultBlockState();
        require(!state.isAir(), "missing client fixture block " + id); return state;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (; type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (; type != null; type = type.getSuperclass()) {
            try { Method m = type.getDeclaredMethod(name); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
