package com.nstut.endless.testing;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real game-mode interactions select points; native Create handlers dispatch both server packets. */
public final class LiveCreateSurvivalClientTest {
    public static final String PASS = "ENDLESS_CREATE_SURVIVAL_CLIENT_PASS";
    private static int lane, ticks;
    private static boolean dispatched, done;
    private LiveCreateSurvivalClientTest() {}
    public static void tick(Minecraft mc) {
        if (done || !Boolean.getBoolean("endless.liveJoinCreateTest") || !Boolean.getBoolean("endless.liveJoinTest.highY") || mc.player == null) return;
        try {
            BlockPos flag = LiveCreateSurvivalServerTest.flag(lane);
            if (mc.level.getBlockState(flag).is(Blocks.LIME_WOOL)) {
                require(dispatched, "server survival completion without client settings dispatch");
                if (++lane == 2) { done = true; System.out.println(PASS + " gameModeRightClicks=true nativeCreatePackets=true authoritativeAcknowledgements=true aliases4096=true"); return; }
                dispatched = false; ticks = 0; return;
            }
            if (ticks % 100 == 0 && ticks > 0) System.out.println("ENDLESS_CREATE_SURVIVAL_CLIENT_PROGRESS lane=" + lane + " dispatched=" + dispatched + " flag=" + mc.level.getBlockState(flag) + " input=" + mc.level.getBlockState(LiveCreateSurvivalServerTest.input(lane)) + " eye=" + mc.player.getEyePosition() + " mode=" + mc.gameMode.getPlayerMode());
            if (!mc.level.getBlockState(flag).is(Blocks.GOLD_BLOCK)) return;
            require(++ticks < 600, "client survival acknowledgement timeout lane=" + lane);
            if (dispatched) return;
            if (!mc.level.getBlockState(LiveCreateSurvivalServerTest.input(lane)).is(BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:depot")))) return;
            if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(LiveCreateSurvivalServerTest.input(lane)).add(0, 0, -.5)) > 4.5
                || mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(LiveCreateSurvivalServerTest.target(lane)).add(0, 0, -.5)) > 4.5) return;
            require(mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SURVIVAL, "native client selections must run in survival mode");
            if (!mc.player.getInventory().getItem(6).is(item("mechanical_arm").getItem())
                || !mc.player.getInventory().getItem(7).is(item("weighted_ejector").getItem())) return;
            int selected = mc.player.getInventory().selected;
            boolean shift = mc.player.isShiftKeyDown();
            var oldHit = mc.hitResult;
            try {
                Class<?> armHandler = Class.forName("com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointHandler");
                mc.player.getInventory().selected = 6;
                armHandler.getMethod("tick").invoke(null);
                click(mc, LiveCreateSurvivalServerTest.input(lane));
                click(mc, LiveCreateSurvivalServerTest.ejector(lane));
                click(mc, LiveCreateSurvivalServerTest.ejector(lane)); // TAKE -> DEPOSIT
                require(((List<?>) field(armHandler, "currentSelection")).size() == 2, "native player arm selection events did not run");
                armHandler.getMethod("flushSettings", BlockPos.class).invoke(null, LiveCreateSurvivalServerTest.arm(lane));
                Class<?> ejectorHandler = Class.forName("com.simibubi.create.content.logistics.depot.EjectorTargetHandler");
                mc.player.getInventory().selected = 7;
                mc.player.input.shiftKeyDown = true;
                mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                ejectorHandler.getMethod("tick").invoke(null);
                click(mc, LiveCreateSurvivalServerTest.target(lane));
                require(LiveCreateSurvivalServerTest.target(lane).equals(field(ejectorHandler, "currentSelection")), "native player ejector selection event did not run");
                ejectorHandler.getMethod("flushSettings", BlockPos.class).invoke(null, LiveCreateSurvivalServerTest.ejector(lane));
                dispatched = true; System.out.println("ENDLESS_CREATE_SURVIVAL_DISPATCH lane=" + lane);
            } finally {
                mc.player.getInventory().selected = selected;
                if (!shift) mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                mc.player.input.shiftKeyDown = shift;
                mc.hitResult = oldHit;
            }
        } catch (Throwable failure) { done = true; System.out.println("ENDLESS_LIVE_JOIN_TEST_FAIL survivalCreate=" + failure); failure.printStackTrace(); mc.stop(); }
    }
    private static void click(Minecraft mc, BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, -.5), Direction.NORTH, pos, false); mc.hitResult = hit;
        require(mc.player.getEyePosition().distanceTo(hit.getLocation()) <= 4.5, "survival selection outside normal player reach: " + pos);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }
    private static Object field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private static ItemStack item(String id) { return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse("create:" + id))); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
