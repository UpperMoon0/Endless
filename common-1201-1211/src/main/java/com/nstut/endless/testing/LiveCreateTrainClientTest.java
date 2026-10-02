package com.nstut.endless.testing;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Require native train graph packets and actual client passenger movement at every origin. */
public final class LiveCreateTrainClientTest {
    private static final Set<Integer> passed = new HashSet<>();
    private static java.util.UUID vehicle;
    private static Vec3 first;
    private static boolean done;
    private static java.util.UUID acknowledged;
    private static int ticks, lastButtonAt = -100;
    private LiveCreateTrainClientTest() {}
    public static void tick(Minecraft mc) {
        if (done || !Boolean.getBoolean(LiveCreateTrainServerTest.PROPERTY) || mc.player == null) return;
        try {
            var entity = mc.player.getVehicle();
            if (++ticks % 200 == 0) System.out.println("ENDLESS_CREATE_TRAIN_CLIENT_WAIT vehicle=" + entity + " player=" + mc.player.position() + " passed=" + passed);
            if (entity == null && passed.size() == LiveCreateTrainServerTest.HEIGHTS.length && acknowledged != null) { done = true; System.out.println("ENDLESS_CREATE_TRAIN_CLIENT_PASS cases=9"); return; }
            if (entity == null || !entity.getClass().getSimpleName().equals("CarriageContraptionEntity")) return;
            if (!entity.getUUID().equals(vehicle)) { vehicle = entity.getUUID(); first = entity.position(); }
            if (entity.position().distanceTo(first) < 16) return;
            int height = java.util.Arrays.stream(LiveCreateTrainServerTest.HEIGHTS).filter(y -> Math.abs(y-entity.getY()) < 4).findFirst().orElseThrow();
            Object carriage = entity.getClass().getMethod("getCarriage").invoke(entity);
            Object train = carriage.getClass().getField("train").get(carriage), graph = train.getClass().getField("graph").get(train);
            if (graph == null) return;
            var nodes = (Set<?>) graph.getClass().getMethod("getNodes").invoke(graph);
            if (nodes.isEmpty()) return;
            for (Object node : nodes) {
                Vec3 location = (Vec3) node.getClass().getMethod("getLocation").invoke(node);
                if (Math.abs(location.y - height) > .01) throw new IllegalStateException("native synchronized graph truncated train height expected="+height+" actual="+location.y);
            }
            if (Math.abs(mc.player.getY()-entity.getY()) > 6) throw new IllegalStateException("client train passenger aliased height");
            if (passed.add(height)) System.out.println("ENDLESS_CREATE_TRAIN_CLIENT_CASE_PASS y="+height+" nativeGraphPacket=true clientRideDistance=16");
            var button = new net.minecraft.core.BlockPos(82, height + 3, 48);
            if (ticks - lastButtonAt >= 40 && Math.abs(((Number) train.getClass().getField("speed").get(train)).doubleValue()) < .01
                    && mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(button)) < 4.5 && mc.level.getBlockState(button).is(net.minecraft.world.level.block.Blocks.STONE_BUTTON)) {
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(button), net.minecraft.core.Direction.UP, button, false));
                acknowledged = entity.getUUID(); lastButtonAt = ticks;
            }
        } catch (Throwable failure) { done = true; System.out.println("ENDLESS_LIVE_JOIN_TEST_FAIL trainWorkflow="+failure); failure.printStackTrace(); mc.stop(); }
    }
}
