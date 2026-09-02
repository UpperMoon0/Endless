package com.nstut.endless.testing;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Real-server high-Y integration smoke used only by the live-join harness. */
public final class LiveHighYServerTest {
    public static final String SYSTEM_PROPERTY = "endless.liveJoinHighYTest";
    public static final String PASS_MARKER = "ENDLESS_HIGH_Y_SERVER_PASS";
    public static final String FAIL_MARKER = "ENDLESS_HIGH_Y_SERVER_FAIL";
    public static final int TEST_Y = 1_000_000;
    public static final BlockPos TEST_POS = new BlockPos(0, TEST_Y, 0);
    public static final BlockPos WATER_POS = new BlockPos(1, TEST_Y, 0);
    public static final BlockPos CHEST_POS = new BlockPos(2, TEST_Y, 0);
    public static final BlockPos POI_POS = new BlockPos(3, TEST_Y, 0);

    private static boolean prepared;
    private static boolean done;
    private static int ticksWithPlayer;
    private static int preparedTick;

    private LiveHighYServerTest() {}

    public static void tick(MinecraftServer server) {
        if (done || !Boolean.parseBoolean(System.getProperty(SYSTEM_PROPERTY, "false"))) return;
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ticksWithPlayer++;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player.serverLevel();

        try {
            if (!prepared) {
                if (ticksWithPlayer < 10) return;
                prepare(level, player);
                prepared = true;
                preparedTick = ticksWithPlayer;
                return;
            }
            if (ticksWithPlayer < preparedTick + 5) return;

            requirePoi(level, "high-Y POI was not registered/searchable");
            ChunkPos poiChunk = new ChunkPos(POI_POS);
            ExtendedPoiStorage.flush(level, poiChunk);
            ExtendedPoiStorage.unload(level, poiChunk);
            requirePoi(level, "high-Y POI did not survive sparse POI flush + eviction + reload");

            done = true;
            System.out.println(PASS_MARKER
                + " y=" + TEST_Y
                + " height=" + level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0)
                + " blockLight=" + level.getBrightness(LightLayer.BLOCK, TEST_POS));
        } catch (Throwable t) {
            done = true;
            System.out.println(FAIL_MARKER + " error=" + t);
            t.printStackTrace();
            player.connection.disconnect(Component.literal("Endless high-Y integration test failed: " + t));
        }
    }

    private static void prepare(ServerLevel level, ServerPlayer player) {
        require(level.setBlock(TEST_POS, Blocks.GLOWSTONE.defaultBlockState(), 3), "glowstone write failed");
        require(level.setBlock(WATER_POS, Blocks.WATER.defaultBlockState(), 3), "water write failed");
        require(level.setBlock(CHEST_POS, Blocks.CHEST.defaultBlockState(), 3), "chest write failed");
        require(level.setBlock(POI_POS, Blocks.RED_BED.defaultBlockState(), 3), "bed/POI write failed");

        require(level.getBlockState(TEST_POS).is(Blocks.GLOWSTONE), "high-Y block read mismatch");
        require(level.getFluidState(WATER_POS).isSource(), "high-Y fluid state missing");
        require(level.getBlockEntity(CHEST_POS) != null, "high-Y block entity was not created");
        require(level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0) == TEST_Y + 1,
            "sparse heightmap did not include high-Y block");
        require(level.getBrightness(LightLayer.BLOCK, TEST_POS) >= 15,
            "sparse block light did not include glowstone emission");

        MinecraftVerticalWorld vertical = EndlessVerticalEngine.world(level);
        vertical.flushDirty();
        EndlessVerticalEngine.close(level);
        require(level.getBlockState(TEST_POS).is(Blocks.GLOWSTONE),
            "high-Y block did not survive sparse page flush + cache reload");
        require(level.getFluidState(WATER_POS).isSource(),
            "high-Y fluid did not survive sparse page flush + cache reload");
        require(level.getBlockState(CHEST_POS).is(Blocks.CHEST),
            "high-Y block-entity state did not survive sparse page reload");
        require(level.getBlockEntity(CHEST_POS) != null,
            "high-Y block entity disappeared after sparse page reload");
        require(level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0) == TEST_Y + 1,
            "heightmap cache reload lost sparse top block");
        require(level.getBrightness(LightLayer.BLOCK, TEST_POS) >= 15,
            "light rebuild after cache reload lost glowstone emission");

        player.teleportTo(0.5D, TEST_Y + 2.0D, 0.5D);
    }

    private static void requirePoi(ServerLevel level, String message) {
        require(level.getPoiManager().existsAtPosition(PoiTypes.HOME, POI_POS), message + " (direct)");
        require(level.getPoiManager().findClosest(
                holder -> holder.is(PoiTypes.HOME), POI_POS, 16, PoiManager.Occupancy.ANY)
            .filter(POI_POS::equals).isPresent(), message + " (search)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
