package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.nio.file.Files;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;

/** Both clock hands must reattach to their saved controller and follow native world time. */
public final class LiveCreateClockworkRestartTest {
    private static final int[] SEAMS = {64, -64, 320, -512, 512, -2048, 2048, -1_000_448, 1_000_448};
    private static ListTag cases;
    private static int ticks;
    private static float[] firstAngles;
    private LiveCreateClockworkRestartTest() {}
    private static BlockPos bearing(int seam) { return new BlockPos(138, seam - 1, 70); }
    private static java.nio.file.Path checkpoint(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("endless-create-clockwork-checkpoint.snbt");
    }
    public static void prepare(ServerLevel level) throws Exception {
        ListTag entries = new ListTag();
        // A native time target away from the initial zero angle exercises actual movement.
        level.setDayTime(1000);
        for (int seam : SEAMS) {
            BlockPos bearing = bearing(seam);
            for (BlockPos p : BlockPos.betweenClosed(bearing.offset(-3, -1, -3), bearing.offset(3, 5, 3)))
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
            level.setBlock(bearing, block("clockwork_bearing").setValue(BlockStateProperties.FACING, Direction.UP), 3);
            // Honey and slime separate the adjacent native hour and minute assemblies.
            level.setBlock(bearing.above(), Blocks.SLIME_BLOCK.defaultBlockState(), 18);
            level.setBlock(bearing.above().east(), Blocks.WHITE_WOOL.defaultBlockState(), 18);
            level.setBlock(bearing.above(2), Blocks.HONEY_BLOCK.defaultBlockState(), 18);
            level.setBlock(bearing.above(2).west(), Blocks.RED_WOOL.defaultBlockState(), 18);
            level.setBlock(bearing.below(), block("creative_motor").setValue(BlockStateProperties.FACING, Direction.UP), 3);
            Object motor = level.getBlockEntity(bearing.below()), speed = field(motor, "generatedSpeed"), controller = level.getBlockEntity(bearing);
            speed.getClass().getMethod("setValue", int.class).invoke(speed, 64);
            for (int i = 0; i < 10; i++) { call(motor, "tick"); call(controller, "tick"); }
            if (field(controller, "hourHand") == null) call(controller, "assemble");
            CompoundTag entry = new CompoundTag(); entry.putInt("Seam", seam);
            for (String hand : List.of("hourHand", "minuteHand")) {
                Entity entity = (Entity) field(controller, hand); require(entity != null && entity.isAlive(), "native clockwork failed to assemble " + hand + " seam=" + seam);
                entry.putUUID(hand, entity.getUUID()); entry.put(hand + "Payload", LiveCreateNbt.writeContraption(level, call(entity, "getContraption")));
            }
            entries.add(entry);
        }
        CompoundTag root = new CompoundTag(); root.put("Cases", entries); Files.writeString(checkpoint(level), root.toString());
        System.out.println("ENDLESS_CREATE_CLOCKWORK_RESTART_PREPARED cases=9 bothNativeHands=true");
    }
    public static boolean verify(ServerLevel level) throws Exception {
        if (cases == null) {
            cases = TagParser.parseTag(Files.readString(checkpoint(level))).getList("Cases", 10);
            require(cases.size() == SEAMS.length, "clockwork checkpoint cases missing"); firstAngles = new float[cases.size()];
            // Change the actual world clock, then allow the native bearing to follow it.
            level.setDayTime(4000);
            for (int i = 0; i < cases.size(); i++) {
                CompoundTag entry = cases.getCompound(i); require(entry.getInt("Seam") == SEAMS[i], "clockwork seam mismatch");
                Object controller = level.getBlockEntity(bearing(entry.getInt("Seam")));
                firstAngles[i] = ((Number) field(controller, "hourAngle")).floatValue();
                for (String hand : List.of("hourHand", "minuteHand")) {
                    Entity entity = level.getEntity(entry.getUUID(hand)); require(entity != null && entity.isAlive(), "world-loaded clockwork hand missing");
                    CompoundTag loaded = LiveCreateNbt.writeContraption(level, call(entity, "getContraption"));
                    for (String key : List.of("Blocks", "Anchor", "HandType", "offset", "facing"))
                        require(java.util.Objects.equals(entry.getCompound(hand + "Payload").get(key), loaded.get(key)), "restored clock hand payload changed " + key);
                }
            }
        }
        if (++ticks < 20) return false;
        for (int i = 0; i < cases.size(); i++) {
            CompoundTag entry = cases.getCompound(i); BlockPos bearing = bearing(entry.getInt("Seam")); Object controller = level.getBlockEntity(bearing);
            for (String hand : List.of("hourHand", "minuteHand"))
                require(field(controller, hand) == level.getEntity(entry.getUUID(hand)), "native saved clock hand did not reattach to controller");
            require(((Number) field(controller, "hourAngle")).floatValue() != firstAngles[i], "restored clock hand did not follow native world time");
            call(controller, "disassemble");
            require(level.getBlockState(bearing.above()).is(Blocks.SLIME_BLOCK) && level.getBlockState(bearing.above().east()).is(Blocks.WHITE_WOOL)
                && level.getBlockState(bearing.above(2)).is(Blocks.HONEY_BLOCK) && level.getBlockState(bearing.above(2).west()).is(Blocks.RED_WOOL), "native clockwork disassembly changed exact hand positions");
        }
        System.out.println("ENDLESS_CREATE_CLOCKWORK_RESTART_PASS cases=9 freshJvm=true bothHands=true nativeControllerRecovery=true worldTimeMovement=true exactDisassembly=true");
        return true;
    }
    private static Object call(Object object, String name) throws Exception { return object.getClass().getMethod(name).invoke(object); }
    private static Object field(Object object, String name) throws Exception { for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(object); } catch (NoSuchFieldException ignored) {} throw new NoSuchFieldException(name); }
    private static BlockState block(String id) { var key = ResourceLocation.tryParse("create:" + id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned block " + id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
