package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;

/** Survival windmill/transmission and in-progress packaging across a fresh world JVM. */
public final class LiveCreateFactoryRestartTest {
    public static final String PREPARED = "ENDLESS_CREATE_FACTORY_RESTART_PREPARED";
    public static final String PASS = "ENDLESS_CREATE_FACTORY_RESTART_PASS";
    private static final int[] SEAMS = {64, -64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private LiveCreateFactoryRestartTest() {}
    private static Path checkpoint(ServerLevel level) { return level.getServer().getWorldPath(LevelResource.ROOT).resolve("endless-create-factory-checkpoint.snbt"); }

    public static void prepare(ServerLevel level) throws Exception {
        level.setChunkForced(2, 2, true);
        ListTag entries = new ListTag();
        for (int seam : SEAMS) {
            BlockPos bearing = new BlockPos(42, seam + 2, 42);
            for (int x = 39; x <= 45; x++) for (int z = 39; z <= 45; z++) for (int y = seam - 5; y <= seam + 7; y++)
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(bearing, block("windmill_bearing").setValue(BlockStateProperties.FACING, Direction.UP), 18);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                level.setBlock(bearing.offset(x, 1, z), Blocks.SLIME_BLOCK.defaultBlockState(), 18);
                level.setBlock(bearing.offset(x, 2, z), Blocks.WHITE_WOOL.defaultBlockState(), 18);
            }
            level.setBlock(bearing.offset(2, 1, 0), block("red_seat"), 18);
            level.setBlock(bearing.below(), axis("shaft"), 3);
            level.setBlock(bearing.below(2), axis("gearshift"), 3);
            level.setBlock(bearing.below(3), axis("clutch"), 3);
            level.setBlock(bearing.below(4), axis("large_cogwheel"), 3);
            level.setBlock(bearing.below(4).offset(1, 0, 1), axis("cogwheel"), 3);
            Object controller = level.getBlockEntity(bearing);
            for (int i = 0; i < 5; i++) call(controller, "tick");
            call(controller, "assemble");
            for (int i = 0; i < 20; i++) tickDrive(level, bearing);
            float rpm = speed(controller);
            require(rpm != 0 && speed(level.getBlockEntity(bearing.below(4))) == rpm
                && speed(level.getBlockEntity(bearing.below(4).offset(1, 0, 1))) == -2 * rpm, "survival windmill/cog ratio failed seam=" + seam);
            require(((Number) call(controller, "calculateAddedStressCapacity")).floatValue() > 0, "windmill supplies no native stress capacity");
            Entity entity = (Entity) field(controller, "movedContraption");
            require(entity != null && entity.isAlive(), "windmill did not assemble native sails");
            require(((List<?>) call(call(entity, "getContraption"), "getSeats")).size() == 1, "windmill did not capture its native seat");
            var rider = net.minecraft.world.entity.EntityType.PIG.create(level);
            require(rider != null, "could not create survival passenger");
            rider.setNoAi(true); rider.setPos(bearing.getX()+.5, bearing.getY()+3, bearing.getZ()+.5);
            require(level.addFreshEntity(rider), "could not add passenger to world");
            entity.getClass().getMethod("addSittingPassenger", Entity.class, int.class).invoke(entity, rider, 0);
            require(rider.getVehicle() == entity, "native seat rejected passenger");
            CompoundTag entry = new CompoundTag();
            entry.putInt("Seam", seam); entry.putUUID("Windmill", entity.getUUID());
            entry.putUUID("Rider", rider.getUUID());
            entry.putFloat("RPM", rpm); entry.put("Contraption", LiveCreateNbt.writeContraption(level, call(entity, "getContraption")));
            BlockPos sender = new BlockPos(34, seam - 1, 34), receiver = new BlockPos(38, seam - 1, 34);
            for (BlockPos p : List.of(sender, receiver)) {
                level.setBlock(p.below(), Blocks.CHEST.defaultBlockState(), 18);
                level.setBlock(p, block("packager").setValue(BlockStateProperties.FACING, Direction.UP), 3);
                for (int i = 0; i < 5; i++) call(level.getBlockEntity(p), "tick");
            }
            ((ChestBlockEntity) level.getBlockEntity(sender.below())).setItem(0, new ItemStack(Items.DIAMOND, 7));
            level.setBlock(sender.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            Object packager = level.getBlockEntity(sender);
            require(!((ItemStack) field(packager, "heldBox")).isEmpty(), "native redstone did not package chest items");
            require(((ChestBlockEntity) level.getBlockEntity(sender.below())).isEmpty(), "packaging left duplicate source items");
            require(((Number) field(packager, "animationTicks")).intValue() > 0, "package was not active at save");
            entry.put("Packager", LiveCreateNbt.save(level, (net.minecraft.world.level.block.entity.BlockEntity) packager));
            entries.add(entry);
        }
        CompoundTag root = new CompoundTag(); root.put("Cases", entries);
        Files.writeString(checkpoint(level), root.toString());
        System.out.println(PREPARED + " cases=9 survivalWindmills=true nativeCogRatios=true activePackaging=true");
    }

    private static ListTag restoredEntries;
    private static int recoveryTicks;
    private static final float[] recoveryAngles = new float[SEAMS.length];

    public static boolean verify(ServerLevel level) throws Exception {
        if (restoredEntries == null) {
            restoredEntries = TagParser.parseTag(Files.readString(checkpoint(level))).getList("Cases", 10);
            require(restoredEntries.size() == SEAMS.length, "factory restart checkpoint cases missing");
            for (int i = 0; i < restoredEntries.size(); i++) {
                CompoundTag entry = restoredEntries.getCompound(i); int seam = entry.getInt("Seam");
                require(seam == SEAMS[i], "factory checkpoint duplicate/reordered seam");
                BlockPos bearing = new BlockPos(42, seam + 2, 42);
                Entity entity = level.getEntity(entry.getUUID("Windmill"));
                require(entity != null && entity.isAlive(), "world-loaded windmill missing seam=" + seam);
                Object controller = level.getBlockEntity(bearing);
                // Native world entity ticks reattach the controller after initial chunk loading.
                CompoundTag saved = entry.getCompound("Contraption"), loaded = LiveCreateNbt.writeContraption(level, call(entity, "getContraption"));
                for (String key : List.of("Blocks", "Anchor", "Sails", "Seats", "Passengers"))
                    require(java.util.Objects.equals(saved.get(key), loaded.get(key)), "restored windmill payload changed " + key);
                recoveryAngles[i] = ((Number) field(controller, "angle")).floatValue();
            }
            return false;
        }
        recoveryTicks++;
        if (recoveryTicks != 20 && recoveryTicks != 40 && recoveryTicks != 60 && recoveryTicks != 80) return false;
        for (int i = 0; i < restoredEntries.size(); i++) {
            CompoundTag entry = restoredEntries.getCompound(i); int seam = entry.getInt("Seam");
            BlockPos bearing = new BlockPos(42, seam + 2, 42);
            Object controller = level.getBlockEntity(bearing); float rpm = entry.getFloat("RPM");
            Entity rider = level.getEntity(entry.getUUID("Rider"));
            require(rider != null && rider.isAlive(), "saved passenger missing after fresh JVM");
            if (recoveryTicks == 20) {
                require(field(controller, "movedContraption") == level.getEntity(entry.getUUID("Windmill")), "restored windmill lost native controller after world ticks");
                require(speed(controller) == rpm && ((Number) field(controller, "angle")).floatValue() != recoveryAngles[i], "restored windmill failed to rotate on world ticks");
                require(speed(level.getBlockEntity(bearing.below(4).offset(1, 0, 1))) == -2 * rpm, "restored cog ratio changed");
                Entity vehicle = level.getEntity(entry.getUUID("Windmill"));
                require(rider.getVehicle() == vehicle, "restored seat passenger lost its vehicle");
                // ServerLevel ticks passengers before block entities. The bearing has
                // advanced one angle by this post-server-tick assertion, so the rider
                // occupies the exact previous transform used by native positionRider.
                net.minecraft.world.phys.Vec3 expected = (net.minecraft.world.phys.Vec3) vehicle.getClass().getMethod("getPassengerPosition", Entity.class, float.class).invoke(vehicle, rider, 0f);
                // Native positionRider adds the entity-specific seat offset and -1/8
                // after getPassengerPosition computes the transformed seat vector.
                double seatOffset = ((Number) Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatEntity")
                    .getMethod("getCustomEntitySeatOffset", Entity.class).invoke(null, rider)).doubleValue();
                expected = expected.add(0, seatOffset - .125, 0);
                require(rider.position().distanceTo(expected) < .01, "native passenger tick did not preserve transformed seat position seam=" + seam
                    + " actual=" + rider.position() + " expected=" + expected + " offset=" + seatOffset
                    + " previous=" + vehicle.getClass().getMethod("getPassengerPosition", Entity.class, float.class).invoke(vehicle, rider, 0f)
                    + " controllerAngle=" + field(controller, "angle"));
                level.setBlock(bearing.below(3).east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            } else if (recoveryTicks == 40) {
                require(speed(level.getBlockEntity(bearing.below(4))) == 0, "powered clutch did not disconnect");
                level.setBlock(bearing.below(3).east(), Blocks.AIR.defaultBlockState(), 3);
            } else if (recoveryTicks == 60) {
                require(speed(level.getBlockEntity(bearing.below(4))) == rpm, "clutch failed to recover");
                level.setBlock(bearing.below(2).west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                require(rider.getVehicle() == level.getEntity(entry.getUUID("Windmill")), "seat passenger fell out while machine restarted/reversed");
                rider.stopRiding();
            } else {
                require(speed(level.getBlockEntity(bearing.below(4))) == -rpm, "scheduled gearshift did not reverse on world ticks");
                require(rider.getVehicle() == null && Math.abs(rider.getY() - bearing.getY()) < 16, "native seat dismount shifted passenger height");
                BlockPos sender = new BlockPos(34, seam - 1, 34), receiver = new BlockPos(38, seam - 1, 34);
                Object packager = level.getBlockEntity(sender), unpacker = level.getBlockEntity(receiver);
                require(((ChestBlockEntity) level.getBlockEntity(sender.below())).isEmpty(), "source duplicated after restart");
                Object inventory = field(packager, "inventory"), target = field(unpacker, "inventory");
                ItemStack box = (ItemStack) inventory.getClass().getMethod("extractItem", int.class, int.class, boolean.class).invoke(inventory, 0, 1, false);
                require(!box.isEmpty(), "world-loaded package missing");
                ItemStack leftover = (ItemStack) target.getClass().getMethod("insertItem", int.class, ItemStack.class, boolean.class).invoke(target, 0, box, false);
                require(leftover.isEmpty(), "native unpacker rejected package");
                ChestBlockEntity output = (ChestBlockEntity) level.getBlockEntity(receiver.below()); int diamonds = 0;
                for (int slot = 0; slot < output.getContainerSize(); slot++) {
                    ItemStack stack = output.getItem(slot);
                    require(stack.isEmpty() || stack.is(Items.DIAMOND), "unexpected unpacked payload"); diamonds += stack.getCount();
                }
                require(diamonds == 7 && ((ItemStack) field(packager, "heldBox")).isEmpty(), "package loss/duplication after restart");
            }
        }
        if (recoveryTicks != 80) return false;
        System.out.println(PASS + " cases=9 freshJvm=true nativeWorldWindmills=true naturalTicks=true cogRatios=true clutchRecovery=true gearshiftReversal=true passengerRecovery=true nativeSeatDismount=true packageItems=7");
        return true;
    }
    private static void tickDrive(ServerLevel level, BlockPos bearing) throws Exception {
        for (BlockPos p : List.of(bearing, bearing.below(), bearing.below(2), bearing.below(3), bearing.below(4), bearing.below(4).offset(1, 0, 1))) call(level.getBlockEntity(p), "tick");
    }
    private static float speed(Object object) throws Exception { return ((Number) call(object, "getSpeed")).floatValue(); }
    private static Object call(Object object, String name) throws Exception { return object.getClass().getMethod(name).invoke(object); }
    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) try {
            Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object);
        } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }
    private static BlockState axis(String id) { return block(id).setValue(BlockStateProperties.AXIS, Direction.Axis.Y); }
    private static BlockState block(String id) { var key = ResourceLocation.tryParse("create:" + id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned Create block " + id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
