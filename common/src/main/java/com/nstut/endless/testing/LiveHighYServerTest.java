package com.nstut.endless.testing;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** Real-server high-Y persistence smoke used only by the live-join harness. */
public final class LiveHighYServerTest {
    public static final String SYSTEM_PROPERTY = "endless.liveJoinHighYTest";
    public static final String PASS_MARKER = "ENDLESS_HIGH_Y_SERVER_PASS";
    public static final String FAIL_MARKER = "ENDLESS_HIGH_Y_SERVER_FAIL";
    public static final int TEST_Y = 1_000_000;
    public static final BlockPos TEST_POS = new BlockPos(0, TEST_Y, 0);

    private static boolean done;
    private static int ticksWithPlayer;

    private LiveHighYServerTest() {}

    public static void tick(MinecraftServer server) {
        if (done || !Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY, "false"))) {
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if (++ticksWithPlayer < 10) {
            return;
        }
        done = true;

        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();
        try {
            boolean placed = level.setBlock(TEST_POS, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            if (!placed || !level.getBlockState(TEST_POS).is(Blocks.DIAMOND_BLOCK)) {
                throw new IllegalStateException("high-Y block write was not visible through Level access");
            }

            MinecraftVerticalWorld vertical = EndlessVerticalEngine.world(level);
            vertical.flushDirty();
            EndlessVerticalEngine.close(level);

            if (!level.getBlockState(TEST_POS).is(Blocks.DIAMOND_BLOCK)) {
                throw new IllegalStateException("high-Y block did not survive sparse page flush + cache reload");
            }

            player.teleportTo(0.5D, TEST_Y + 2.0D, 0.5D);
            System.out.println(PASS_MARKER + " y=" + TEST_Y);
        } catch (Throwable t) {
            System.out.println(FAIL_MARKER + " error=" + t);
            t.printStackTrace();
        }
    }
}
