package com.nstut.endless.testing;

import com.nstut.endless.compat.create.CreateFullPosition;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;

/** Real world-saved entities, controllers, mounted inventories and actors across two JVMs. */
public final class LiveCreateMovingRestartTest {
    public static final String PREPARED = "ENDLESS_CREATE_MOVING_RESTART_PREPARED";
    public static final String PASS = "ENDLESS_CREATE_MOVING_RESTART_PASS";
    private LiveCreateMovingRestartTest() {}
    private static Path checkpoint(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("endless-live-moving-checkpoint.snbt"); }

    public static void prepare(ServerLevel level) throws Exception {
        ListTag cases = new ListTag();
        for (int y : new int[]{-1_000_200, 1_000_200}) {
            int x = 2;
            for (String id : List.of("mechanical_bearing", "rope_pulley", "mechanical_piston")) {
                BlockPos controllerPos = new BlockPos(x, y, 13); x += 5;
                boolean pulley = id.equals("rope_pulley"), piston = id.equals("mechanical_piston");
                BlockState state = block(id);
                if (!pulley) state = state.setValue(BlockStateProperties.FACING, Direction.UP);
                Direction.Axis axis = (Direction.Axis) state.getBlock().getClass().getMethod("getRotationAxis", BlockState.class).invoke(state.getBlock(), state);
                Direction drive = Direction.get(Direction.AxisDirection.POSITIVE, axis);
                BlockPos motorPos = controllerPos.relative(drive.getOpposite());
                BlockPos payload = pulley ? controllerPos.below() : controllerPos.above();
                require(level.getBlockState(controllerPos).isAir() && level.getBlockState(payload).isAir(), "moving restart corridor is occupied");
                level.setBlock(controllerPos, state, 18);
                if (piston) for (int pole = 1; pole <= 32; pole++) level.setBlock(controllerPos.below(pole),
                    block("piston_extension_pole").setValue(BlockStateProperties.FACING, Direction.UP), 18);
                level.setBlock(payload, Blocks.SLIME_BLOCK.defaultBlockState(), 18);
                level.setBlock(payload.east(), Blocks.CHEST.defaultBlockState(), 18);
                ((ChestBlockEntity) level.getBlockEntity(payload.east())).setItem(0, new ItemStack(Items.DIAMOND, 7));
                level.setBlock(payload.west(), block("mechanical_drill").setValue(BlockStateProperties.FACING, Direction.WEST), 18);
                level.setBlock(motorPos, block("creative_motor").setValue(BlockStateProperties.FACING, drive), 3);
                Object motor = level.getBlockEntity(motorPos), controller = level.getBlockEntity(controllerPos);
                Object mode = field(controller, "movementMode");
                Class<?> modeType = Class.forName("com.simibubi.create.content.contraptions.IControlContraption$" + (id.equals("mechanical_bearing") ? "RotationMode" : "MovementMode"));
                Object neverPlace = java.util.Arrays.stream(modeType.getEnumConstants()).filter(e -> e.toString().endsWith("NEVER_PLACE")).findFirst().orElseThrow();
                mode.getClass().getMethod("setValue", int.class).invoke(mode, ((Enum<?>) neverPlace).ordinal());
                Object speed = field(motor, "generatedSpeed"); speed.getClass().getMethod("setValue", int.class).invoke(speed, piston ? -8 : 8);
                for (int i = 0; i < 10; i++) { call(motor, "tick"); call(controller, "tick"); }
                if (!Boolean.TRUE.equals(field(controller, "running"))) declared(controller, "assemble");
                Entity entity = (Entity) field(controller, "movedContraption");
                require(entity != null && entity.isAlive(), "moving restart machine did not assemble: " + id);
                for (int i = 0; i < 4; i++) { call(motor, "tick"); call(controller, "tick"); entity.tick(); }
                // Keep the assembled entity parked while the dedicated server runs without
                // a client during startup. Native NEVER_PLACE retains the assembly at zero RPM.
                speed.getClass().getMethod("setValue", int.class).invoke(speed, 0);
                for (int tick = 0; tick < 4; tick++) { call(motor, "tick"); call(controller, "tick"); entity.tick(); }
                require(entity.isAlive() && Boolean.TRUE.equals(field(controller, "running")), "parked assembly was disassembled");
                CompoundTag oracle = LiveCreateNbt.writeContraption(level, call(entity, "getContraption"));
                require(!oracle.getList("Actors", 10).isEmpty(), "restart fixture has no actor");
                CompoundTag entry = new CompoundTag();
                entry.putString("Machine", id); entry.putString("Entity", entity.getUUID().toString());
                CreateFullPosition.put(entry, "Controller", controllerPos);
                CreateFullPosition.put(entry, "Motor", motorPos);
                entry.put("Contraption", oracle);
                CompoundTag entityNBT = new CompoundTag(); entity.saveWithoutId(entityNBT);
                entry.put("EntityPos", entityNBT.get("Pos").copy());
                cases.add(entry);
            }
        }
        CompoundTag saved = new CompoundTag(); saved.put("Cases", cases);
        Files.writeString(checkpoint(level), saved.toString());
        System.out.println(PREPARED + " nativeWorldEntities=6 inventories=true actors=true independentCheckpoint=true");
    }

    public static void verify(ServerLevel level) throws Exception {
        CompoundTag checkpoint = TagParser.parseTag(Files.readString(checkpoint(level)));
        ListTag cases = checkpoint.getList("Cases", 10);
        require(cases.size() == 6, "missing independent moving restart cases");
        for (int i = 0; i < cases.size(); i++) {
            CompoundTag entry = cases.getCompound(i);
            String id = entry.getString("Machine");
            BlockPos pos = CreateFullPosition.get(entry, "Controller");
            Entity entity = level.getEntity(UUID.fromString(entry.getString("Entity")));
            require(entity != null && entity.isAlive(), "world-saved moving entity did not reload: " + id + " " + pos);
            Object controller = level.getBlockEntity(pos), motor = level.getBlockEntity(CreateFullPosition.get(entry, "Motor"));
            // Actual entity tick must perform Create's native controller discovery/attachment.
            entity.tick();
            require(field(controller, "movedContraption") == entity, "fresh-JVM entity did not attach to its saved controller");
            CompoundTag oracle = entry.getCompound("Contraption");
            CompoundTag restored = LiveCreateNbt.writeContraption(level, call(entity, "getContraption"));
            for (String key : List.of("Blocks", "Anchor", "Interactors", "Seats", "Passengers"))
                require(java.util.Objects.equals(oracle.get(key), restored.get(key)), "fresh-JVM exact payload changed: " + id + " " + key);
            // Actor motion evolves while the real server ticks; stable actor positions/types and
            // mounted storage contents must remain exact, rather than comparing moving velocity.
            ListTag actorsA = oracle.getList("Actors", 10), actorsB = restored.getList("Actors", 10);
            require(actorsA.size() == actorsB.size() && !actorsB.isEmpty(), "fresh-JVM actors changed");
            for (int actor = 0; actor < actorsA.size(); actor++) require(java.util.Objects.equals(actorsA.getCompound(actor).get("Pos"), actorsB.getCompound(actor).get("Pos")), "fresh-JVM actor position shifted");
            require(!oracle.getList("items", 10).isEmpty(), "restart fixture did not capture mounted inventory");
            for (String key : List.of("items", "fluids", "interactable_positions", "Storage", "FluidStorage"))
                require(java.util.Objects.equals(oracle.get(key), restored.get(key)), "fresh-JVM mounted inventory changed: " + key);
            ListTag savedPos = entry.getList("EntityPos", 6);
            require(savedPos.size() == 3 && entity.getX() == savedPos.getDouble(0) && entity.getY() == savedPos.getDouble(1)
                && entity.getZ() == savedPos.getDouble(2), "parked fresh-JVM entity position changed");
            Object speed = field(motor, "generatedSpeed");
            speed.getClass().getMethod("setValue", int.class).invoke(speed, id.equals("mechanical_piston") ? -8 : 8);
            var before = entity.position(); float angle = id.equals("mechanical_bearing") ? ((Number) field(controller, "angle")).floatValue() : 0;
            for (int tick = 0; tick < 8; tick++) { call(motor, "tick"); call(controller, "tick"); entity.tick(); }
            require(id.equals("mechanical_bearing") ? ((Number) field(controller, "angle")).floatValue() != angle : entity.position().distanceTo(before) > .01,
                "fresh-JVM restored machine did not resume motion: " + id);
            declared(controller, "disassemble");
            require(!entity.isAlive(), "restored machine did not disassemble its own entity");
            LiveCreateDisassemblyTest.verify(level, entity, 1);
        }
        System.out.println(PASS + " freshJvm=true nativeWorldEntities=6 controllerLinkage=true resumedMotion=true exactBlocks=true inventories=true actors=true restoredEntityDisassembly=true");
    }
    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try {
            Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(target);
        } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }
    private static Object declared(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try {
            Method m = type.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(target);
        } catch (NoSuchMethodException ignored) {}
        throw new NoSuchMethodException(name);
    }
    private static Object call(Object target, String name) throws Exception { return target.getClass().getMethod(name).invoke(target); }
    private static BlockState block(String name) { return BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + name)).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
