package com.nstut.endless.testing;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Two-process persistence test. Phase B runs in a fresh dedicated-server JVM on phase A's world. */
public final class LiveColdRestartServerTest {
    public static final String PHASE_PROPERTY = "endless.liveJoinColdRestartPhase";
    public static final String PHASE_A_PASS = "ENDLESS_COLD_RESTART_PHASE_A_PASS";
    public static final String PHASE_B_PASS = "ENDLESS_COLD_RESTART_PHASE_B_PASS";
    public static final String FAIL_MARKER = "ENDLESS_COLD_RESTART_FAIL";

    private static boolean done;
    private static int ticks;

    private LiveColdRestartServerTest() {}

    public static void tick(MinecraftServer server) {
        String phase = System.getProperty(PHASE_PROPERTY, "").trim();
        if (done || phase.isEmpty() || server.getPlayerList().getPlayers().isEmpty()) return;
        ticks++;
        if (ticks < 10) return;
        ServerLevel level = server.overworld();
        try {
            if (phase.equalsIgnoreCase("A")) {
                if (ticks == 10) prepare(level);
                if (ticks < 18) return;
                verify(level);
                ExtendedPoiStorage.flush(level, new ChunkPos(poiPos()));
                EndlessVerticalEngine.world(level).flushDirty();
                require(server.saveEverything(true, true, true), "dedicated server saveEverything reported failure");
                done = true;
                System.out.println(PHASE_A_PASS + " worldSaved=true");
            } else if (phase.equalsIgnoreCase("B")) {
                verify(level);
                done = true;
                System.out.println(PHASE_B_PASS + " freshJvm=true");
            } else {
                throw new IllegalArgumentException("unknown cold-restart phase: " + phase);
            }
        } catch (Throwable t) {
            done = true;
            System.out.println(FAIL_MARKER + " phase=" + phase + " error=" + t);
            t.printStackTrace();
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private static void prepare(ServerLevel level) {
        require(level.setBlock(glowPos(), Blocks.GLOWSTONE.defaultBlockState(), 3), "cold-restart glowstone write failed");
        require(level.setBlock(waterPos(), Blocks.WATER.defaultBlockState(), 3), "cold-restart water write failed");
        require(level.setBlock(chestPos(), Blocks.CHEST.defaultBlockState(), 3), "cold-restart chest write failed");
        require(level.setBlock(powerPos(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3), "cold-restart redstone source write failed");
        require(level.setBlock(lampPos(), Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true), 3),
            "cold-restart lamp write failed");
        require(level.setBlock(poiPos(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 3),
            "cold-restart POI write failed");
    }

    private static void verify(ServerLevel level) {
        require(level.getBlockState(glowPos()).is(Blocks.GLOWSTONE), "cold-restart sparse block missing");
        require(level.getFluidState(waterPos()).isSource(), "cold-restart sparse fluid missing");
        require(level.getBlockState(chestPos()).is(Blocks.CHEST) && level.getBlockEntity(chestPos()) != null,
            "cold-restart sparse block entity missing");
        require(level.getBlockState(powerPos()).is(Blocks.REDSTONE_BLOCK), "cold-restart redstone source missing");
        require(level.getBlockState(lampPos()).is(Blocks.REDSTONE_LAMP)
                && level.getBlockState(lampPos()).getValue(BlockStateProperties.LIT),
            "cold-restart lit redstone lamp state missing");
        require(level.getBrightness(LightLayer.BLOCK, glowPos()) >= 15, "cold-restart sparse light state missing");
        boolean poi = level.getPoiManager().findClosest(
            holder -> holder.is(PoiTypes.HOME), poiPos(), 1, PoiManager.Occupancy.ANY).filter(poiPos()::equals).isPresent();
        require(poi, "cold-restart sparse POI missing");
    }

    private static BlockPos glowPos() { return new BlockPos(0, -3072, 10); }
    private static BlockPos waterPos() { return new BlockPos(1, 3072, 10); }
    private static BlockPos chestPos() { return new BlockPos(2, -3072, 10); }
    private static BlockPos powerPos() { return new BlockPos(4, 3072, 10); }
    private static BlockPos lampPos() { return new BlockPos(5, 3072, 10); }
    private static BlockPos poiPos() { return new BlockPos(6, -3072, 10); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
