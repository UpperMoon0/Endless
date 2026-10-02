package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/** Native station assembly, conductor schedule, passenger travel and disassembly. */
public final class LiveCreateTrainServerTest {
    public static final String PROPERTY = "endless.liveCreateTrains";
    public static final int[] HEIGHTS = {64, -65, 319, -513, 511, -2049, 2047, -1_000_449, 1_000_447};
    private static int lane, step, ticks;
    private static boolean done;
    private static Object train;
    private static Entity carriage;
    private static net.minecraft.world.phys.Vec3 departure;
    private LiveCreateTrainServerTest() {}
    private static BlockPos origin() { return new BlockPos(80, HEIGHTS[lane], 104); }
    private static BlockPos destination() { return origin().north(56); }
    private static BlockPos signal() { return origin().north(28).east(3); }
    public static void tick(MinecraftServer server) {
        if (done || !Boolean.getBoolean(PROPERTY) || server.getPlayerList().getPlayers().isEmpty()) return;
        ServerLevel level = server.overworld(); var player = server.getPlayerList().getPlayers().get(0);
        try {
            require(++ticks < 800, "native train timed out lane=" + lane + " step=" + step);
            if (ticks % 100 == 0 && train != null) System.out.println("ENDLESS_CREATE_TRAIN_SERVER_WAIT lane=" + lane + " step=" + step + " position=" + carriage.position() + " speed=" + field(train, "speed") + " station=" + call(train, "getCurrentStation") + " destination=" + call(level.getBlockEntity(destination().east(3)), "getStation") + " button=" + level.getBlockState(destination().east(2).above(3)));
            if (step == 0) {
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
                for (Entity mob : level.getAllEntities()) if (mob instanceof net.minecraft.world.entity.Mob) mob.discard();
                for (int z = 2; z <= 9; z++) level.setChunkForced(5, z, true);
                for (int z = 2; z <= 9; z++) if (!level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(5, z))) return;
                for (BlockPos p : BlockPos.betweenClosed(origin().offset(-1, -1, -72), origin().offset(4, 6, 40)))
                    level.setBlock(p, p.getY() == origin().getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 18);
                for (int z = -72; z <= 40; z++) level.setBlock(origin().south(z), value(block("track"), "shape", "zo"), 3);
                station(level, origin()); station(level, destination());
                level.setBlock(origin().east(2).above(2), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(origin().east(2).above(3), Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
                level.setBlock(destination().east(2).above(2), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(destination().east(2).above(3), Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
                level.setBlock(signal(), block("track_signal"), 3);
                Object signalTarget = field(level.getBlockEntity(signal()), "edgePoint");
                fieldObject(signalTarget, "targetTrack").set(signalTarget, origin().north(28).subtract(signal()));
                fieldObject(signalTarget, "targetDirection").set(signalTarget, Direction.AxisDirection.POSITIVE);
                player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                player.teleportTo(origin().getX()+3.5, origin().getY(), origin().getZ()+.5);
                player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                step = 1; ticks = 0;
            } else if (step == 1 && ticks >= 60) {
                Object station = level.getBlockEntity(origin().east(3));
                require(call(station, "getStation") != null, "native source station has no graph point");
                require((boolean) station.getClass().getMethod("enterAssemblyMode", net.minecraft.server.level.ServerPlayer.class).invoke(station, player), "native train assembly mode refused");
                BlockPos bogey = origin().south().above(), frame = bogey.above();
                level.setBlock(bogey, block("small_bogey").setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.Z), 3);
                for (BlockPos p : BlockPos.betweenClosed(frame.offset(0, 0, -1), frame.offset(1, 0, 1))) level.setBlock(p, Blocks.SLIME_BLOCK.defaultBlockState(), 18);
                level.setBlock(frame.north().above(), block("controls").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 18);
                level.setBlock(frame.above(), value(block("blaze_burner"), "blaze", "smouldering"), 18);
                level.setBlock(frame.east().above(), block("red_seat"), 18);
                level.setBlock(frame.south().above(), Blocks.CHEST.defaultBlockState(), 18);
                ((ChestBlockEntity) level.getBlockEntity(frame.south().above())).setItem(0, new ItemStack(Items.DIAMOND, 7));
                Object manager = Class.forName("com.simibubi.create.Create").getField("RAILWAYS").get(null);
                Map<?, ?> trains = (Map<?, ?>) field(manager, "trains"); var before = java.util.Set.copyOf(trains.keySet());
                station.getClass().getMethod("assemble", java.util.UUID.class).invoke(station, player.getUUID());
                train = trains.entrySet().stream().filter(e -> !before.contains(e.getKey())).map(Map.Entry::getValue).findFirst().orElseThrow(() -> new IllegalStateException("native train assembly failed"));
                require((boolean) call(station, "exitAssemblyMode"), "native assembly mode did not exit");
                step = 6; ticks = 0;
            } else if (step == 6 && ticks >= 10) {
                Object car = ((List<?>) field(train, "carriages")).get(0);
                carriage = (Entity) call(car, "anyAvailableEntity");
                if (carriage == null) { require(ticks < 100, "native carriage missing after normal world initialization"); return; }
                require(carriage.isAlive(), "native carriage died during initialization");
                Object contraption = call(carriage, "getContraption"); require(((List<?>) call(contraption, "getSeats")).size() == 1, "train passenger seat missing");
                carriage.getClass().getMethod("addSittingPassenger", Entity.class, int.class).invoke(carriage, player, 0);
                require(player.getVehicle() == carriage, "survival train passenger failed to mount");
                departure = carriage.position(); step = 2; ticks = 0;
            } else if (step == 2 && ticks >= 40) {
                // Departure waits for a real client use request after the native
                // carriage and graph packets arrive. A slow renderer cannot miss
                // the entire movement and first observe this train at its end.
                if (!level.getBlockState(origin().east(2).above(3)).getValue(BlockStateProperties.POWERED)) return;
                require((boolean) call(train, "hasForwardConductor"), "native blaze conductor not detected");
                require(call(level.getBlockEntity(signal()), "getState").toString().equals("GREEN"), "unoccupied native track signal is not green actual=" + call(level.getBlockEntity(signal()), "getState"));
                Object dest = call(level.getBlockEntity(destination().east(3)), "getStation"); require(dest != null, "destination station missing");
                fieldObject(dest, "name").set(dest, "Endless-train-" + HEIGHTS[lane]);
                Object instruction = Class.forName("com.simibubi.create.content.trains.schedule.destination.DestinationInstruction").getConstructor().newInstance();
                ((CompoundTag) call(instruction, "getData")).putString("Text", "Endless-train-" + HEIGHTS[lane]);
                Object entry = Class.forName("com.simibubi.create.content.trains.schedule.ScheduleEntry").getConstructor().newInstance(); fieldObject(entry, "instruction").set(entry, instruction);
                Object schedule = Class.forName("com.simibubi.create.content.trains.schedule.Schedule").getConstructor().newInstance();
                @SuppressWarnings("unchecked") List<Object> entries = (List<Object>) field(schedule, "entries"); entries.add(entry); fieldObject(schedule, "cyclic").setBoolean(schedule, false);
                Object runtime = field(train, "runtime"); runtime.getClass().getMethod("setSchedule", schedule.getClass(), boolean.class).invoke(runtime, schedule, false);
                step = 3; ticks = 0;
            } else if (step == 3) {
                require(player.getVehicle() == carriage && carriage.isAlive(), "train lost its survival passenger during travel");
                require(Math.abs(carriage.getY() - HEIGHTS[lane]) < 4 && Math.abs(player.getY()-carriage.getY()) < 6, "train/passenger height changed during native travel");
                require(!(boolean) field(train, "derailed"), "native scheduled train derailed");
                Object dest = call(level.getBlockEntity(destination().east(3)), "getStation");
                if (call(train, "getCurrentStation") != dest || carriage.position().distanceTo(departure) < 40) return;
                if (Math.abs(((Number) field(train, "speed")).doubleValue()) >= .01) return;
                require(call(level.getBlockEntity(signal()), "getState").toString().equals("RED"), "native signal did not mark the occupied destination segment red");
                step = 4; ticks = 0;
            } else if (step == 4) {
                if (!level.getBlockState(destination().east(2).above(3)).getValue(BlockStateProperties.POWERED)) return;
                step = 7; ticks = 0;
            } else if (step == 7 && ticks >= 40) {
                player.stopRiding(); require(Math.abs(player.getY() - HEIGHTS[lane]) < 8, "train dismount aliased height");
                require((boolean) train.getClass().getMethod("disassemble", Direction.class, BlockPos.class).invoke(train, Direction.SOUTH, destination().above()), "native arrived train could not disassemble");
                BlockPos chest = destination().south(2).above(3);
                require(level.getBlockEntity(chest) instanceof ChestBlockEntity, "native train disassembly misplaced mounted chest " + chest);
                ChestBlockEntity inventory = (ChestBlockEntity) level.getBlockEntity(chest); int diamonds = 0;
                for (int i = 0; i < inventory.getContainerSize(); i++) { var item = inventory.getItem(i); require(item.isEmpty() || item.is(Items.DIAMOND), "train inventory identity changed"); diamonds += item.getCount(); }
                require(diamonds == 7, "native train lost or duplicated mounted diamonds");
                step = 5; ticks = 0;
            } else if (step == 5 && ticks >= 40) {
                require(call(level.getBlockEntity(signal()), "getState").toString().equals("GREEN"), "native signal did not clear after train disassembly");
                System.out.println("ENDLESS_CREATE_TRAIN_CASE_PASS y=" + HEIGHTS[lane] + " nativeAssembly=true conductorSchedule=true passengerRide=true nativeArrival=true signalOccupiedAndClear=true exactDiamonds=7 nativeDisassembly=true");
                if (++lane == HEIGHTS.length) { done = true; System.out.println("ENDLESS_CREATE_TRAIN_SERVER_PASS cases=9"); }
                else { step = ticks = 0; train = null; carriage = null; }
            }
        } catch (Throwable failure) { done = true; System.out.println("ENDLESS_HIGH_Y_SERVER_FAIL trainWorkflow=" + failure); failure.printStackTrace(); }
    }
    private static void station(ServerLevel level, BlockPos track) throws Exception {
        BlockPos pos = track.east(3); level.setBlock(pos, block("track_station"), 3);
        Object behaviour = field(level.getBlockEntity(pos), "edgePoint"); fieldObject(behaviour, "targetTrack").set(behaviour, track.subtract(pos));
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static BlockState value(BlockState state, String name, String value) {
        Property property = state.getProperties().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
        return state.setValue(property, (Comparable) property.getValue(value).orElseThrow());
    }
    private static Object call(Object object, String method) throws Exception { return object.getClass().getMethod(method).invoke(object); }
    private static Object field(Object object, String name) throws Exception { return fieldObject(object, name).get(object); }
    private static Field fieldObject(Object object, String name) throws Exception { for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {} throw new NoSuchFieldException(name); }
    private static BlockState block(String id) { var key = ResourceLocation.tryParse("create:"+id); require(BuiltInRegistries.BLOCK.containsKey(key), "missing pinned block "+id); return BuiltInRegistries.BLOCK.get(key).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
