package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Client native selection packets must configure the authoritative survival machines. */
public final class LiveCreateSurvivalServerTest {
    public static final String PASS = "ENDLESS_CREATE_SURVIVAL_SERVER_PASS";
    private static int stage, ticks;
    private static boolean prepared, configured, held, launched, fed, done;
    private LiveCreateSurvivalServerTest() {}
    public static BlockPos arm(int lane) { return new BlockPos(2, EndlessHeights.getMaxBuildHeight() - 6 - lane * 4096, 1); }
    public static BlockPos input(int lane) { return arm(lane).west().above(); }
    public static BlockPos ejector(int lane) { return arm(lane).east(2).above(); }
    public static BlockPos target(int lane) { return ejector(lane).east(3); }
    public static BlockPos flag(int lane) { return arm(lane).east(7); }

    public static void tick(MinecraftServer server) {
        if (done || !Boolean.getBoolean("endless.liveJoinCreateTest") || !Boolean.getBoolean("endless.liveJoinHighYTest")) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerLevel level = server.overworld(); ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        try {
            if (!prepared) {
                if (!level.getBlockState(LiveHighYServerTest.upperPersistentTargetPos()).is(Blocks.STONE)) return;
                for (int lane = 0; lane < 2; lane++) prepare(level, lane);
                player.setGameMode(GameType.SURVIVAL);
                player.setNoGravity(false);
                player.getInventory().setItem(6, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse("create:mechanical_arm"))));
                player.getInventory().setItem(7, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse("create:weighted_ejector"))));
                player.inventoryMenu.broadcastChanges();
                player.teleportTo(4.5, arm(0).getY() + 2, 2.5);
                prepared = true;
                return;
            }
            require(++ticks < 600, "survival Create transfer did not finish within 600 server ticks stage=" + stage);
            Object arm = level.getBlockEntity(arm(stage)), ejector = level.getBlockEntity(ejector(stage));
            List<?> inputs = (List<?>) field(arm, "inputs"), outputs = (List<?>) field(arm, "outputs");
            if (!inputs.isEmpty() || !outputs.isEmpty()) {
                require(inputs.size() == 1 && outputs.size() == 1, "server arm selection count mismatch");
                require(input(stage).equals(call(inputs.get(0), "getPos")) && ejector(stage).equals(call(outputs.get(0), "getPos")), "server arm selection coordinates aliased");
                configured = true;
            }
            if (!fed && configured && target(stage).equals(call(ejector, "getTargetPosition"))) {
                level.getBlockEntity(input(stage)).getClass().getMethod("setHeldItem", ItemStack.class).invoke(level.getBlockEntity(input(stage)), new ItemStack(Items.DIAMOND, 7));
                fed = true;
            }
            held |= !((ItemStack) field(arm, "heldItem")).isEmpty();
            launched |= !((List<?>) field(ejector, "launchedItems")).isEmpty();
            ItemStack arrived = (ItemStack) call(level.getBlockEntity(target(stage)), "getHeldItem");
            if (ticks % 100 == 0) System.out.println("ENDLESS_CREATE_SURVIVAL_PROGRESS lane=" + stage + " configured=" + configured + " held=" + held + " launched=" + launched + " arrived=" + arrived + " armSpeed=" + call(arm, "getSpeed") + " ejectorSpeed=" + call(ejector, "getSpeed") + " phase=" + field(arm, "phase") + " player=" + player.position() + " target=" + call(ejector, "getTargetPosition") + " ejectorState=" + call(ejector, "getState") + " powered=" + field(ejector, "powered") + " stackLimit=" + call(field(ejector, "maxStackSize"), "getValue") + " source=" + call(level.getBlockEntity(input(stage)), "getHeldItem") + " ejectorStack=" + call(field(ejector, "depotBehaviour"), "getHeldItemStack") + " present=" + call(field(ejector, "depotBehaviour"), "getPresentStackSize"));
            if (arrived.getCount() != 7) return;
            require(arrived.is(Items.DIAMOND) && configured && held && launched, "transfer skipped native selection, arm motion or ejector flight");
            require(target(stage).equals(call(ejector, "getTargetPosition")), "server ejector selection packet targeted the wrong height");
            require(((ItemStack) call(level.getBlockEntity(input(stage)), "getHeldItem")).isEmpty(), "input inventory duplicated");
            require(((ItemStack) field(arm, "heldItem")).isEmpty() && ((List<?>) field(ejector, "launchedItems")).isEmpty(), "items duplicated in moving stages");
            require(((Number) call(field(ejector, "depotBehaviour"), "getPresentStackSize")).intValue() == 0
                && ((List<?>) field(field(ejector, "depotBehaviour"), "incoming")).isEmpty(), "ejector inventory duplicated the delivered stack");
            require(player.getInventory().items.stream().noneMatch(stack -> stack.is(Items.DIAMOND)), "selection clicks collected the transfer payload");
            level.setBlock(flag(stage), Blocks.LIME_WOOL.defaultBlockState(), 3);
            System.out.println("ENDLESS_CREATE_SURVIVAL_CASE_PASS lane=" + stage + " nativeClientPackets=true survival=true armTransfer=true ejectorArrival=7");
            if (++stage == 2) { done = true; System.out.println(PASS + " alias4096=true nativeClientPackets=true inventoryConserved=true normalServerTicks=true"); }
            else {
                configured = held = launched = fed = false;
                player.teleportTo(4.5, arm(stage).getY() + 2, 2.5);
            }
        } catch (Throwable failure) {
            done = true; System.out.println("ENDLESS_HIGH_Y_SERVER_FAIL survivalCreate=" + failure); failure.printStackTrace();
        }
    }
    private static void prepare(ServerLevel level, int lane) throws Exception {
        level.setBlock(arm(lane), block("mechanical_arm"), 3);
        level.setBlock(input(lane), block("depot"), 3);
        level.setBlock(ejector(lane), block("weighted_ejector").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), 3);
        level.setBlock(target(lane), block("depot"), 3);
        level.setBlock(new BlockPos(4, arm(lane).getY() + 1, 2), Blocks.STONE.defaultBlockState(), 3);
        // ArmBlock is a small cog without a shaft face. A neighbouring Y cog
        // meshes with it; the creative motor drives that cog through its shaft.
        BlockPos armCog = arm(lane).east();
        level.setBlock(armCog, block("cogwheel").setValue(BlockStateProperties.AXIS, Direction.Axis.Y), 3);
        for (BlockPos machine : List.of(armCog, ejector(lane))) {
            BlockState state = level.getBlockState(machine);
            Direction.Axis axis = (Direction.Axis) state.getBlock().getClass().getMethod("getRotationAxis", BlockState.class).invoke(state.getBlock(), state);
            Direction drive = Direction.get(Direction.AxisDirection.POSITIVE, axis);
            BlockPos motor = machine.relative(drive.getOpposite());
            level.setBlock(motor, block("creative_motor").setValue(BlockStateProperties.FACING, drive), 3);
            Object speed = field(level.getBlockEntity(motor), "generatedSpeed"); speed.getClass().getMethod("setValue", int.class).invoke(speed, 256);
        }
        Object limit = field(level.getBlockEntity(ejector(lane)), "maxStackSize"); limit.getClass().getMethod("setValue", int.class).invoke(limit, 7);
        level.setBlock(flag(lane), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
    }
    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) try {
            Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object);
        } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object object, String name) throws Exception { return object.getClass().getMethod(name).invoke(object); }
    private static BlockState block(String id) { return BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:" + id)).defaultBlockState(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
