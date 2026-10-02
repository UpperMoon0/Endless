package com.nstut.endless.testing;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Sends the same native packets and item-use requests as Create's player UI. */
public final class LiveCreatePlayerWorkflowClientTest {
    private static int lane;
    private static Block last;
    private static int readyTicks;
    private static boolean done;
    private LiveCreatePlayerWorkflowClientTest() {}
    public static void tick(Minecraft mc) {
        if (done || !Boolean.getBoolean(LiveCreatePlayerWorkflowServerTest.PROPERTY) || mc.player == null || mc.level == null) return;
        try {
            Block flag = mc.level.getBlockState(LiveCreatePlayerWorkflowServerTest.flag(lane)).getBlock();
            if (flag == last || flag == Blocks.AIR) return;
            BlockPos base = LiveCreatePlayerWorkflowServerTest.base(lane);
            if (mc.player.position().distanceTo(Vec3.atCenterOf(base)) > 5) return;
            if (flag == Blocks.GOLD_BLOCK) {
                send(Class.forName("com.simibubi.create.content.equipment.toolbox.ToolboxEquipPacket").getConstructor(BlockPos.class, int.class, int.class).newInstance(base, 0, 0));
            } else if (flag == Blocks.EMERALD_BLOCK) {
                send(Class.forName("com.simibubi.create.content.equipment.toolbox.ToolboxEquipPacket").getConstructor(BlockPos.class, int.class, int.class).newInstance(null, 0, 0));
            } else if (flag == Blocks.DIAMOND_BLOCK) {
                click(mc, 1, base.east(2)); click(mc, 4, base.east(4));
            } else if (flag == Blocks.LAPIS_BLOCK) {
                mc.player.setYRot(0); mc.player.setYHeadRot(0);
                mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(0, 0, mc.player.onGround()));
                click(mc, 2, base.south(2));
            } else if (flag == Blocks.WHITE_WOOL) {
                click(mc, 3, base.south(3));
            } else if (flag == Blocks.REDSTONE_BLOCK) {
                click(mc, 5, base.east(8));
            } else if (flag == Blocks.NETHERITE_BLOCK) {
                if (mc.player.getOffhandItem().isEmpty() || ++readyTicks < 10) return;
                click(mc, 5, base.east(8)); readyTicks = 0;
            } else if (flag == Blocks.BLUE_WOOL) {
                // Server observes normal underwater breathing ticks before and after equipping the diving suit.
            } else if (flag == Blocks.LIME_WOOL) {
                if (++lane == LiveCreatePlayerWorkflowServerTest.HEIGHTS.length) { done = true; System.out.println("ENDLESS_CREATE_PLAYER_CLIENT_PASS nativePackets=true survivalItemUses=true cases=" + lane); }
                last = null; return;
            } else return;
            last = flag;
        } catch (Throwable failure) { done = true; System.out.println("ENDLESS_LIVE_JOIN_TEST_FAIL playerWorkflows=" + failure); failure.printStackTrace(); mc.stop(); }
    }
    private static void click(Minecraft mc, int slot, BlockPos pos) {
        if (mc.gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL) throw new IllegalStateException("player workflow requires survival");
        mc.player.getInventory().selected = slot;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, .5, 0), Direction.UP, pos, false);
        if (mc.player.getEyePosition().distanceTo(hit.getLocation()) > (slot == 5 ? 8 : 4.5)) throw new IllegalStateException("fixture exceeds the tested reach");
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }
    private static void send(Object packet) throws Exception {
        Object network;
        try { network = Class.forName("com.simibubi.create.AllPackets").getMethod("getChannel").invoke(null); }
        catch (NoSuchMethodException modern) { network = Class.forName("net.createmod.catnip.platform.CatnipServices").getField("NETWORK").get(null); }
        for (var method : network.getClass().getMethods()) if (method.getName().equals("sendToServer") && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(packet)) { method.invoke(network, packet); return; }
        throw new NoSuchMethodException("native Create sendToServer");
    }
}
