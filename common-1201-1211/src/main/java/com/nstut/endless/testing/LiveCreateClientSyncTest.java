package com.nstut.endless.testing;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Real client packet and Flywheel-instance regression, enabled only by the live Create harness. */
public final class LiveCreateClientSyncTest {
    private static final float[] SPEEDS = {16, -32, 0, 64};
    private static final List<BlockEntity> SHAFTS = new ArrayList<>();
    private static Object visualManager;
    private static Map<?, ?> visuals;
    private static Constructor<?> packetConstructor;
    private static int stage = -1;
    private static int waiting;
    private static boolean done;

    private LiveCreateClientSyncTest() {}

    public static boolean tick(Minecraft minecraft) {
        if (done) return true;
        try {
            if (visualManager == null) initialize(minecraft);
            float speed = stage < 0 ? 0 : SPEEDS[stage];
            String mismatch = mismatch(speed);
            if (mismatch != null) {
                if (++waiting > 100) throw new IllegalStateException(mismatch);
                return false;
            }
            waiting = 0;
            if (++stage < SPEEDS.length) {
                for (BlockEntity shaft : SHAFTS) {
                    CompoundTag tag = new CompoundTag();
                    tag.putFloat("Speed", SPEEDS[stage]);
                    ClientboundBlockEntityDataPacket packet = (ClientboundBlockEntityDataPacket)
                        packetConstructor.newInstance(shaft.getBlockPos(), shaft.getType(), tag);
                    minecraft.getConnection().handleBlockEntityData(packet);
                }
                return false;
            }
            for (BlockEntity shaft : SHAFTS)
                minecraft.level.setBlock(shaft.getBlockPos(), Blocks.AIR.defaultBlockState(), 18);
            done = true;
            System.out.println("ENDLESS_CREATE_ROTATION_SYNC_PASS shafts=" + SHAFTS.size()
                + " stages=start,reverse,stop,restart denseBoundary=true positiveAndNegativeSparse=true");
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            System.out.println("ENDLESS_LIVE_JOIN_TEST_FAIL Create rotation sync: " + failure);
            failure.printStackTrace();
            minecraft.stop();
            done = true;
            return false;
        }
    }

    private static void initialize(Minecraft minecraft) throws ReflectiveOperationException {
        Class<?> api = Class.forName("dev.engine_room.flywheel.api.visualization.VisualizationManager");
        for (var method : api.getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 1) {
                Object manager = method.invoke(null, minecraft.level);
                if (manager == null) throw new IllegalStateException("Flywheel visualization disabled");
                visualManager = manager.getClass().getMethod("blockEntities").invoke(manager);
                break;
            }
        }
        if (visualManager == null) throw new IllegalStateException("No Flywheel manager");
        Object storage = visualManager.getClass().getMethod("getStorage").invoke(visualManager);
        visuals = (Map<?, ?>) field(storage, "visuals");
        packetConstructor = ClientboundBlockEntityDataPacket.class.getDeclaredConstructor(
            BlockPos.class, BlockEntityType.class, CompoundTag.class);
        packetConstructor.setAccessible(true);
        Block shaftBlock = BuiltInRegistries.BLOCK.stream()
            .filter(block -> BuiltInRegistries.BLOCK.getKey(block).toString().equals("create:shaft"))
            .findFirst().orElseThrow(() -> new IllegalStateException("Create shaft missing"));
        int[] bottoms = {207, 1_000_207, -1_000_407};
        for (int line = 0; line < bottoms.length; line++) {
            for (int offset = 0; offset < 200; offset++) {
                BlockPos pos = new BlockPos(10 + line, bottoms[line] + offset, 10);
                if (!minecraft.level.getBlockState(pos).isAir())
                    throw new IllegalStateException("Occupied client fixture " + pos);
                minecraft.level.setBlock(pos, shaftBlock.defaultBlockState()
                    .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y), 18);
                BlockEntity shaft = minecraft.level.getBlockEntity(pos);
                if (shaft == null) throw new IllegalStateException("Missing shaft " + pos);
                SHAFTS.add(shaft);
                // Create the initial stopped visual. Never queue an update here:
                // subsequent refreshes must come from the native packet callback.
                visualManager.getClass().getMethod("queueAdd", Object.class).invoke(visualManager, shaft);
            }
        }
    }

    private static String mismatch(float expected) throws ReflectiveOperationException {
        float[] offsets = {Float.NaN, Float.NaN, Float.NaN};
        for (int index = 0; index < SHAFTS.size(); index++) {
            BlockEntity shaft = SHAFTS.get(index);
            float logical = ((Number) shaft.getClass().getMethod("getSpeed").invoke(shaft)).floatValue();
            Object visual = visuals.get(shaft);
            if (visual == null) return "No visual at " + shaft.getBlockPos();
            Object instance = field(visual, "rotatingModel");
            float rendered = ((Number) field(instance, "rotationalSpeed")).floatValue();
            float offset = ((Number) field(instance, "rotationOffset")).floatValue();
            int line = index / 200;
            if (Float.isNaN(offsets[line])) offsets[line] = offset;
            if (logical != expected || rendered != expected * 6 || offsets[line] != offset)
                return "Shaft " + shaft.getBlockPos() + " expectedRPM=" + expected
                    + " logicalRPM=" + logical + " renderedDegreesPerSecond=" + rendered
                    + " phase=" + offset + " expectedPhase=" + offsets[line];
        }
        return null;
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Flywheel keeps storage and rotating instances on superclass fields.
            }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }
}
