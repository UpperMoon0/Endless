package com.nstut.endless.testing;

import com.nstut.endless.compat.create.CreateFullPosition;
import com.nstut.endless.compat.create.CreateLogicalGeometry;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Real optional Create APIs, run by both canonical dedicated-loader cold-restart lanes. */
public final class LiveCreateExpandedMachinesTest {
    public static final String PASS = "ENDLESS_CREATE_EXPANDED_MACHINES_PASS";
    private static final int[] SEAMS = {-64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private LiveCreateExpandedMachinesTest() {}

    public static void run(ServerLevel level) throws ReflectiveOperationException {
        for (int seam : SEAMS) {
            verifyVerticalNetwork(level, seam);
            verifyPulley(level, seam - 2);
            verifyMovement(level, seam);
            verifyDisplayAndLinks(level, seam);
        }
        verifyElevator(level);
        System.out.println(PASS + " seams=8 lowerAndUpperDense=true positiveAndNegativePage=true packedEdges=true million=true"
            + " nativeStress=true savedReload=true displayReservations=true linkRelocation=true pulleyLimits=true elevatorDiskDiscovery=true bearingPistonPulleyMovement=true");
    }

    private static void verifyVerticalNetwork(ServerLevel level, int seam) throws ReflectiveOperationException {
        BlockPos base = new BlockPos(3, seam - 2, 7);
        BlockState motor = block("creative_motor").setValue(BlockStateProperties.FACING, Direction.UP);
        BlockState shaft = block("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        BlockState fan = block("encased_fan").setValue(BlockStateProperties.FACING, Direction.UP);
        BlockState[] states = {motor, shaft, shaft, fan};
        List<BlockEntity> entities = new ArrayList<>();
        BlockState[] originals = new BlockState[states.length];
        for (int i = 0; i < states.length; i++) originals[i] = level.getBlockState(base.above(i));
        try {
            for (int i = 0; i < states.length; i++) {
                level.setBlock(base.above(i), states[i], 3);
                entities.add(level.getBlockEntity(base.above(i)));
            }
            tick(entities, 10);
            Object network = connected(entities);
            float stress = number(call(network, "calculateStress"));
            float capacity = number(call(network, "calculateCapacity"));
            require(stress > 0 && capacity > stress, "real consumer must carry nonzero stress");
            Long identity = (Long) field(entities.get(0), "network");
            List<CompoundTag> saved = new ArrayList<>();
            for (BlockEntity entity : entities) saved.add(LiveCreateNbt.save(level, entity));
            Class<?> kinetic = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
            for (BlockEntity entity : entities) kinetic.getMethod("setNetwork", Long.class).invoke(entity, new Object[]{null});
            for (int i = 0; i < states.length; i++) level.removeBlockEntity(base.above(i));
            entities.clear();
            // Follower-first admission across the actual dense/page boundary.
            for (int i = states.length - 1; i >= 0; i--) {
                BlockEntity restored = LiveCreateNbt.load(level, base.above(i), states[i], saved.get(i));
                require(restored != null, "missing restored network member");
                level.setBlockEntity(restored); entities.add(0, restored);
                call(restored, "tick");
            }
            tick(entities, 260);
            Object restoredNetwork = connected(entities);
            require(identity.equals(field(entities.get(0), "network")), "network identity changed across boundary reload");
            float restoredStress = number(call(restoredNetwork, "calculateStress"));
            float restoredCapacity = number(call(restoredNetwork, "calculateCapacity"));
            require(Math.abs(stress - restoredStress) < .01f && Math.abs(capacity - restoredCapacity) < .01f,
                "boundary reload changed stress/capacity seam=" + seam + " identity=" + identity
                    + " stress=" + stress + "/" + restoredStress + " capacity=" + capacity + "/" + restoredCapacity);
            var update = restoredNetwork.getClass().getMethod("updateCapacityFor", kinetic, float.class);
            update.invoke(restoredNetwork, entities.get(0), 0f);
            for (BlockEntity entity : entities) require(number(call(entity, "getSpeed")) == 0
                && Boolean.TRUE.equals(call(entity, "isOverStressed")), "boundary overstress did not propagate");
            update.invoke(restoredNetwork, entities.get(0), number(call(entities.get(0), "calculateAddedStressCapacity")));
            connected(entities);
        } finally {
            for (int i = states.length - 1; i >= 0; i--) level.setBlock(base.above(i), originals[i], 3);
        }
    }

    private static Object connected(List<BlockEntity> entities) throws ReflectiveOperationException {
        Object network = call(entities.get(0), "getOrCreateNetwork");
        Map<?, ?> members = (Map<?, ?>) field(network, "members");
        require(members.size() == entities.size(), "boundary network membership mismatch");
        require(((Number) call(network, "getSize")).intValue() == entities.size(),
            "boundary network retained duplicate unloaded membership");
        for (BlockEntity entity : entities) require(members.containsKey(entity)
            && call(entity, "getOrCreateNetwork") == network && number(call(entity, "getSpeed")) == 16,
            "boundary member not connected/running at 16 RPM");
        return network;
    }

    private static void verifyPulley(ServerLevel level, int y) throws ReflectiveOperationException {
        BlockPos pos = new BlockPos(5, y, 7);
        level.setBlock(pos, block("rope_pulley"), 18);
        try {
            Object pulley = level.getBlockEntity(pos);
            int range = ((Number) invokeDeclared(pulley, "getExtensionRange")).intValue();
            Object config = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs").getMethod("server").invoke(null);
            Object kinetics = field(config, "kinetics");
            int ropeLimit = ((Number) call(field(kinetics, "maxRopeLength"), "get")).intValue();
            require(range == Math.max(0, Math.min(ropeLimit, y - 1 - EndlessHeights.getMinBuildHeight())),
                "pulley used dense floor at " + pos);
            require(((Number) call(pulley, "getMinValue")).intValue() == EndlessHeights.getMinBuildHeight(),
                "pulley threshold switch used dense floor");
        } finally { level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18); }
    }

    private static void verifyMovement(ServerLevel level, int seam) throws ReflectiveOperationException {
        // Use the actual controller assemble/tick/disassemble lifecycle, not map translations.
        for (String id : new String[]{"mechanical_bearing", "rope_pulley", "mechanical_piston"}) {
            boolean pulley = id.equals("rope_pulley"), piston = id.equals("mechanical_piston");
            BlockPos controllerPos = new BlockPos(12, pulley ? seam + 2 : seam - 2, 7);
            BlockState state = block(id);
            if (!pulley) state = state.setValue(BlockStateProperties.FACING, Direction.UP);
            BlockPos payload = pulley ? controllerPos.below() : controllerPos.above();
            Map<BlockPos, BlockState> originals = new java.util.LinkedHashMap<>();
            // Clear the movement corridor and sticky neighbours: bedrock at the
            // dense floor is an intentional assembly blocker, not a height failure.
            for (int x = 11; x <= 13; x++) for (int z = 6; z <= 8; z++)
                for (int y = seam - 10; y < seam + 8; y++) {
                    BlockPos pos = new BlockPos(x, y, z); originals.put(pos, level.getBlockState(pos));
                }
            Direction.Axis driveAxis = (Direction.Axis) state.getBlock().getClass()
                .getMethod("getRotationAxis", BlockState.class).invoke(state.getBlock(), state);
            Direction drive = Direction.get(Direction.AxisDirection.POSITIVE, driveAxis);
            BlockPos motorPos = controllerPos.relative(drive.getOpposite());
            BlockState motorOriginal = level.getBlockState(motorPos);
            Object controller = null;
            try {
                for (BlockPos pos : originals.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                level.setBlock(controllerPos, state, 18);
                if (piston) for (int i = 1; i <= 4; i++)
                    level.setBlock(controllerPos.below(i), block("piston_extension_pole")
                        .setValue(BlockStateProperties.FACING, Direction.UP), 18);
                level.setBlock(payload, Blocks.SLIME_BLOCK.defaultBlockState(), 18);
                controller = level.getBlockEntity(controllerPos);
                level.setBlock(motorPos, block("creative_motor").setValue(BlockStateProperties.FACING, drive), 3);
                BlockEntity motor = level.getBlockEntity(motorPos);
                Object generatedSpeed = field(motor, "generatedSpeed");
                generatedSpeed.getClass().getMethod("setValue", int.class).invoke(generatedSpeed, piston ? -64 : 64);
                tick(List.of(motor, (BlockEntity) controller), 10);
                require(number(call(controller, "getSpeed")) == (piston ? -64f : 64f),
                    id + " fixture lacks a real powered network");
                if (!Boolean.TRUE.equals(field(controller, "running"))) invokeDeclared(controller, "assemble");
                Object moving = field(controller, "movedContraption");
                require(moving instanceof net.minecraft.world.entity.Entity && Boolean.TRUE.equals(field(controller, "running")),
                    id + " did not assemble at seam " + seam);
                require(level.getBlockState(payload).isAir(), id + " did not remove captured payload");
                net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) moving;
                net.minecraft.world.phys.Vec3 before = entity.position();
                for (int i = 0; i < 16; i++) {
                    call(motor, "tick");
                    call(controller, "tick");
                }
                if (pulley || piston) require(Math.abs(entity.getY() - before.y) >= 1,
                    id + " did not move through boundary at seam " + seam);
                else require(Math.abs(number(field(controller, "angle"))) > 0, "bearing did not rotate");
                Object contraption = call(entity, "getContraption");
                CompoundTag disk = LiveCreateNbt.writeContraption(level, contraption);
                Object restored = Class.forName("com.simibubi.create.content.contraptions.Contraption")
                    .getMethod("fromNBT", Level.class, CompoundTag.class, boolean.class).invoke(null, level, disk, false);
                require(((Map<?, ?>) call(restored, "getBlocks")).size() == ((Map<?, ?>) call(contraption, "getBlocks")).size(),
                    id + " moving payload changed on NBT round trip");
                invokeDeclared(controller, "disassemble");
                int payloadCount = 0;
                for (BlockPos pos : originals.keySet()) if (level.getBlockState(pos).is(Blocks.SLIME_BLOCK)) payloadCount++;
                require(payloadCount == 1, id + " lost or duplicated its payload on disassembly");
                System.out.println("ENDLESS_CREATE_MOVEMENT_CASE_PASS machine=" + id + " seam=" + seam);
            } finally {
                if (controller != null) {
                    Object moved = field(controller, "movedContraption");
                    if (moved instanceof net.minecraft.world.entity.Entity entity) entity.discard();
                }
                level.setBlock(motorPos, Blocks.AIR.defaultBlockState(), 3);
                for (var entry : originals.entrySet()) level.setBlock(entry.getKey(), entry.getValue(), 18);
                level.setBlock(motorPos, motorOriginal, 3);
            }
        }
    }

    private static void verifyDisplayAndLinks(ServerLevel level, int y) throws ReflectiveOperationException {
        BlockPos source = new BlockPos(7, y, 7), alias = source.above(4096), targetPos = source.east();
        List<BlockPos> cleanup = List.of(source, alias, targetPos, source.west(), alias.west());
        try {
            level.setBlock(source, block("display_link"), 18);
            level.setBlock(alias, block("display_link"), 18);
            level.setBlock(targetPos, Blocks.CHEST.defaultBlockState(), 18);
            BlockEntity target = level.getBlockEntity(targetPos);
            Class<?> displayBE = Class.forName("com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity");
            Class<?> contextType = Class.forName("com.simibubi.create.content.redstone.displayLink.DisplayLinkContext");
            Object a = contextType.getConstructor(Level.class, displayBE).newInstance(level, level.getBlockEntity(source));
            Object b = contextType.getConstructor(Level.class, displayBE).newInstance(level, level.getBlockEntity(alias));
            Class<?> displayTarget = Class.forName("com.simibubi.create.api.behaviour.display.DisplayTarget");
            Object renderer = Class.forName("com.simibubi.create.content.redstone.displayLink.target.DisplayBoardTarget").getConstructor().newInstance();
            Method reserve = displayTarget.getMethod("reserve", int.class, BlockEntity.class, contextType);
            Method reserved = displayTarget.getMethod("isReserved", int.class, BlockEntity.class, contextType);
            reserve.invoke(null, 1, target, a);
            require(Boolean.TRUE.equals(reserved.invoke(renderer, 1, target, b)), "alias stole display line");
            CompoundTag saved = LiveCreateNbt.save(level, target);
            BlockEntity restored = LiveCreateNbt.load(level, targetPos, target.getBlockState(), saved);
            restored.setLevel(level);
            require(Boolean.TRUE.equals(reserved.invoke(renderer, 1, restored, b)), "display reservation lost across real NBT reload");
            require(Boolean.FALSE.equals(reserved.invoke(renderer, 1, restored, a)), "owner did not release its line");
            reserve.invoke(null, 2, target, a);
            level.setBlock(source, Blocks.AIR.defaultBlockState(), 18);
            require(Boolean.FALSE.equals(reserved.invoke(renderer, 2, target, b)), "removed display source retained reservation");
            require(!CreateFullPosition.persistentData(target).getCompound("DisplayLink").contains(CreateFullPosition.displayLineKey(2)),
                "stale exact display reservation was not cleaned");

            BlockPos first = source.west(), moved = alias.west();
            var receiverProperty = (net.minecraft.world.level.block.state.properties.BooleanProperty)
                Class.forName("com.simibubi.create.content.redstone.link.RedstoneLinkBlock").getField("RECEIVER").get(null);
            BlockState receiver = block("redstone_link").setValue(receiverProperty, true);
            level.setBlock(first, receiver, 18);
            level.setBlock(moved, receiver, 18);
            // Create registers LinkBehaviour during its first NBT read. A bare
            // initialize() does not exercise that production deferred lifecycle.
            BlockEntity original = LiveCreateNbt.load(level, first, receiver,
                LiveCreateNbt.save(level, level.getBlockEntity(first)));
            BlockEntity relocated = LiveCreateNbt.load(level, moved, receiver,
                LiveCreateNbt.save(level, level.getBlockEntity(moved)));
            require(original != null && relocated != null, "receiver native NBT initialization failed");
            level.setBlockEntity(original); level.setBlockEntity(relocated);
            call(original, "initialize"); call(relocated, "initialize");
            Object originalBehaviour = field(original, "link"), relocatedBehaviour = field(relocated, "link");
            CompoundTag linkTag = new CompoundTag();
            LiveCreateNbt.writeBehaviour(level, originalBehaviour, linkTag);
            LiveCreateNbt.readBehaviour(level, originalBehaviour, linkTag);
            require(Boolean.FALSE.equals(field(originalBehaviour, "newPosition")), "stationary link marked moved");
            LiveCreateNbt.readBehaviour(level, relocatedBehaviour, linkTag);
            require(Boolean.TRUE.equals(field(relocatedBehaviour, "newPosition")), "packed alias relocation was not detected");
            relocatedBehaviour.getClass().getMethod("setReceivedStrength", int.class).invoke(relocatedBehaviour, 11);
            require(((Number) call(relocated, "getReceivedSignal")).intValue() == 11, "relocated receiver discarded real signal callback");
        } finally {
            for (BlockPos pos : cleanup) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        }
    }

    private static void verifyElevator(ServerLevel level) throws ReflectiveOperationException {
        int[] heights = {-1_000_448, -513, -65, 319, 320, 511, 512, 1_000_448};
        List<BlockPos> positions = new ArrayList<>();
        BlockState contact = block("redstone_contact").setValue(BlockStateProperties.FACING, Direction.NORTH);
        for (int y : heights) { BlockPos pos = new BlockPos(9, y, 7); positions.add(pos); level.setBlock(pos, contact, 18); }
        try {
            EndlessVerticalEngine.world(level).flushDirty();
            EndlessVerticalEngine.world(level).unloadColumn(0, 0);
            Class<?> coordsType = Class.forName("com.simibubi.create.content.contraptions.elevator.ElevatorColumn$ColumnCoords");
            Object coords = coordsType.getConstructor(int.class, int.class, Direction.class).newInstance(9, 7, Direction.NORTH);
            Class<?> columnType = Class.forName("com.simibubi.create.content.contraptions.elevator.ElevatorColumn");
            Object column = columnType.getConstructor(LevelAccessor.class, coordsType).newInstance(level, coords);
            long started = System.nanoTime();
            call(column, "gatherAll");
            require(System.nanoTime() - started < 5_000_000_000L, "elevator discovery scanned empty logical height");
            for (BlockPos pos : positions) require(level.getBlockState(pos).is(block("elevator_contact").getBlock()),
                "elevator discovery missed persisted contact " + pos);
            long visited = CreateLogicalGeometry.columnPositions(level, new BlockPos(9, level.getMinBuildHeight(), 7),
                new BlockPos(9, level.getMaxBuildHeight(), 7)).count();
            require(visited < 20_000, "sparse elevator search did not remain proportional to occupied pages");
        } finally { for (BlockPos pos : positions) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18); }
    }

    private static void tick(List<BlockEntity> entities, int count) throws ReflectiveOperationException {
        for (int i = 0; i < count; i++) for (BlockEntity entity : entities) call(entity, "tick");
    }
    private static BlockState block(String id) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + id)).defaultBlockState();
        require(!state.isAir(), "missing Create block " + id); return state;
    }
    private static Object invokeDeclared(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Method m = type.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(name);
    }
    private static Object call(Object object, String name) throws ReflectiveOperationException {
        return object.getClass().getMethod(name).invoke(object);
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static float number(Object value) { return ((Number) value).floatValue(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
