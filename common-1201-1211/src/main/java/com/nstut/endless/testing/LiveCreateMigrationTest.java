package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Opt-in regression: real connected Create machinery restored from legacy NBT. */
public final class LiveCreateMigrationTest {
    private LiveCreateMigrationTest() {}

    public static void run(ServerLevel level) {
        try {
            for (String order : new String[]{"follower-first", "root-first", "late-follower"}) {
                verifyOrder(level, order);
            }
            System.out.println("ENDLESS_CREATE_MIGRATION_PASS orders=follower-first,root-first,late-follower generators=2 realConsumer=true");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Create connected legacy-network regression failed", e);
        }
    }

    private static void verifyOrder(ServerLevel level, String order) throws ReflectiveOperationException {
        boolean followerFirst = !"root-first".equals(order);
        boolean lateFollower = "late-follower".equals(order);
        BlockPos root = new BlockPos(lateFollower ? 6 : followerFirst ? 2 : 4, 1_000_000, 2);
        BlockPos[] positions = {root, root.south(), root.south(2), root.south(3)};
        BlockState[] states = {
            block("creative_motor").setValue(BlockStateProperties.FACING, Direction.SOUTH),
            block("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Z),
            block("mechanical_press").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
            block("creative_motor").setValue(BlockStateProperties.FACING, Direction.NORTH)
        };
        BlockEntity[] entities = new BlockEntity[positions.length];
        for (int i = 0; i < positions.length; i++) {
            require(level.setBlock(positions[i], states[i], 3), "migration fixture placement failed");
            entities[i] = level.getBlockEntity(positions[i]);
            require(entities[i] != null, "migration fixture missing real Create block entity");
        }
        // Opposite facing: -8 local RPM is +8 on the common shaft axis. The
        // second real generator stays overpowered by the 16-RPM primary motor.
        Object speedBehaviour = field(entities[3], "generatedSpeed");
        speedBehaviour.getClass().getMethod("setValue", int.class).invoke(speedBehaviour, -8);
        tickAll(entities, false, 5);
        assertConnected(entities);
        require(field(entities[0], "source") == null && field(entities[3], "source") != null,
            "fixture must contain a root and an overpowered generator");

        CompoundTag[] saved = new CompoundTag[entities.length];
        long legacy = root.asLong();
        for (int i = 0; i < entities.length; i++) {
            saved[i] = LiveCreateNbt.save(level, entities[i]);
            saved[i].getCompound("Network").putLong("Id", legacy);
        }
        // Retire runtime memberships before reconstructing fresh BEs. The saved
        // NBT is independent and retains the original Source and stress fields.
        Class<?> kinetic = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
        for (BlockEntity entity : entities) kinetic.getMethod("setNetwork", Long.class).invoke(entity, new Object[]{null});
        for (BlockPos pos : positions) level.removeBlockEntity(pos);
        if (lateFollower) {
            // Hide the downstream blocks until the root has actually migrated;
            // merely changing tick order while every BE exists cannot exercise
            // late admission. No network method is called on the restored root.
            for (int i = 1; i < positions.length; i++) level.setBlock(positions[i], Blocks.AIR.defaultBlockState(), 2);
        }
        for (int i = 0; i < entities.length; i++) {
            if (lateFollower && i > 0) {
                level.setBlock(positions[i], states[i], 2);
                level.removeBlockEntity(positions[i]);
            }
            entities[i] = LiveCreateNbt.load(level, positions[i], states[i], saved[i]);
            require(entities[i] != null, "legacy NBT reconstruction failed");
            level.setBlockEntity(entities[i]);
            require(Long.valueOf(legacy).equals(field(entities[i], "network")), "legacy ID was not restored");
            if (lateFollower && i == 0) {
                call(entities[0], "tick");
                call(entities[0], "tick");
                require(!Long.valueOf(legacy).equals(field(entities[0], "network")), "root did not migrate before followers loaded");
            }
        }
        tickAll(entities, followerFirst, 260);
        Object network = assertConnected(entities);
        require(!Long.valueOf(legacy).equals(field(entities[0], "network")), "root did not migrate");
        Map<?, ?> sources = (Map<?, ?>) field(network, "sources");
        require(sources.size() == 2 && sources.containsKey(entities[0]) && sources.containsKey(entities[3]),
            "connected generators are missing from the shared source membership");

        // Exercise real Create stress propagation, not just numeric ID equality.
        var updateCapacity = network.getClass().getMethod("updateCapacityFor", kinetic, float.class);
        updateCapacity.invoke(network, entities[0], 0f);
        updateCapacity.invoke(network, entities[3], 0f);
        for (BlockEntity entity : entities) {
            require(Boolean.TRUE.equals(call(entity, "isOverStressed")) && number(call(entity, "getSpeed")) == 0,
                "zero capacity did not overstress every connected member");
        }
        for (int index : new int[]{0, 3}) {
            updateCapacity.invoke(network, entities[index], number(call(entities[index], "calculateAddedStressCapacity")));
        }
        assertConnected(entities);
        System.out.println("ENDLESS_CREATE_CONNECTED_MIGRATION_ORDER_PASS order="
            + order + " members=4 sources=2 stressRecovery=true");
    }

    private static Object assertConnected(BlockEntity[] entities) throws ReflectiveOperationException {
        Object network = call(entities[0], "getOrCreateNetwork");
        Map<?, ?> members = (Map<?, ?>) field(network, "members");
        float expectedStress = 0;
        float expectedCapacity = 0;
        for (BlockEntity entity : entities) {
            require(call(entity, "getOrCreateNetwork") == network && members.containsKey(entity),
                "connected Create machinery split across network objects");
            require(number(call(entity, "getSpeed")) != 0, "connected machinery stopped");
            expectedStress += number(call(entity, "calculateStressApplied")) * Math.abs(number(call(entity, "getTheoreticalSpeed")));
            expectedCapacity += number(call(entity, "calculateAddedStressCapacity")) * Math.abs(number(call(entity, "getGeneratedSpeed")));
        }
        require(members.size() == entities.length, "stale or duplicate network membership");
        require(expectedStress > 0 && expectedCapacity > expectedStress, "fixture lacks a real stress consumer/capacity");
        require(Math.abs(number(call(network, "calculateStress")) - expectedStress) < .01f, "network stress accounting diverged");
        require(Math.abs(number(call(network, "calculateCapacity")) - expectedCapacity) < .01f, "network capacity accounting diverged");
        for (BlockEntity entity : entities) {
            require(Math.abs(number(field(entity, "stress")) - expectedStress) < .01f, "member stress did not synchronize");
            require(Math.abs(number(field(entity, "capacity")) - expectedCapacity) < .01f, "member capacity did not synchronize");
        }
        return network;
    }

    private static void tickAll(BlockEntity[] entities, boolean reverse, int count) throws ReflectiveOperationException {
        for (int tick = 0; tick < count; tick++) {
            for (int i = 0; i < entities.length; i++) call(entities[reverse ? entities.length - 1 - i : i], "tick");
        }
    }
    private static BlockState block(String id) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + id));
        require(block != Blocks.AIR, "Create fixture block missing: " + id);
        return block.defaultBlockState();
    }
    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static float number(Object value) { return ((Number) value).floatValue(); }
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
