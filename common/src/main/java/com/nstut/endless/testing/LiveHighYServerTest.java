package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real-server extreme-Y integration coverage used only by the live-join harness. */
public final class LiveHighYServerTest {
    public static final String SYSTEM_PROPERTY = "endless.liveJoinHighYTest";
    public static final String PASS_MARKER = "ENDLESS_HIGH_Y_SERVER_PASS";
    public static final String FAIL_MARKER = "ENDLESS_HIGH_Y_SERVER_FAIL";

    public static final int LOWER_Y = EndlessLogicalHeights.MIN_BUILD_HEIGHT;
    public static final int UPPER_Y = EndlessLogicalHeights.MAX_BUILD_HEIGHT - 1;
    public static final double LOWER_PLAYER_Y = LOWER_Y + 2.0D;
    public static final double UPPER_PLAYER_Y = UPPER_Y - 2.0D;

    public static final BlockPos LOWER_TEST_POS = pos(0, LOWER_Y);
    public static final BlockPos LOWER_WATER_POS = pos(1, LOWER_Y);
    public static final BlockPos LOWER_CHEST_POS = pos(2, LOWER_Y);
    public static final BlockPos LOWER_POI_POS = pos(3, LOWER_Y);
    public static final BlockPos LOWER_POWER_POS = pos(4, LOWER_Y);
    public static final BlockPos LOWER_LAMP_POS = pos(5, LOWER_Y);

    public static final BlockPos UPPER_TEST_POS = pos(0, UPPER_Y);
    public static final BlockPos UPPER_WATER_POS = pos(1, UPPER_Y);
    public static final BlockPos UPPER_CHEST_POS = pos(2, UPPER_Y);
    public static final BlockPos UPPER_POI_POS = pos(3, UPPER_Y);
    public static final BlockPos UPPER_POWER_POS = pos(4, UPPER_Y);
    public static final BlockPos UPPER_LAMP_POS = pos(5, UPPER_Y);

    private static boolean prepared;
    private static boolean mechanicsVerified;
    private static boolean movedUpper;
    private static boolean done;
    private static int ticksWithPlayer;
    private static int preparedTick;
    private static int lowerArrivalTick;
    private static ArmorStand lowerStand;
    private static ArmorStand upperStand;

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

            if (!mechanicsVerified) {
                if (ticksWithPlayer < preparedTick + 6) return;
                // Vanilla ServerLevel queues POI registration onto the server executor
                // after a block-state change. Check it on a later tick instead of
                // racing the queued add from the same setBlock call stack.
                requirePoi(level, LOWER_POI_POS, "lower-bound POI was not registered/searchable");
                requirePoi(level, UPPER_POI_POS, "upper-bound POI was not registered/searchable");
                verifyDelayedMechanics(level);
                flushReloadAndVerify(level);
                requireEntityAlive(lowerStand, "lower-bound entity did not survive normal ticking");
                requireEntityAlive(upperStand, "upper-bound entity did not survive normal ticking");
                mechanicsVerified = true;
                player.setNoGravity(true);
                player.teleportTo(0.5D, LOWER_PLAYER_Y, 0.5D);
                lowerArrivalTick = ticksWithPlayer;
                return;
            }

            // Give the real client enough time to receive and render the lower
            // sparse page before moving the same connection to the upper edge.
            if (!movedUpper && ticksWithPlayer >= lowerArrivalTick + 80) {
                player.teleportTo(0.5D, UPPER_PLAYER_Y, 0.5D);
                movedUpper = true;
                return;
            }

            if (movedUpper && ticksWithPlayer >= lowerArrivalTick + 120) {
                done = true;
                System.out.println(PASS_MARKER
                    + " lowerY=" + LOWER_Y
                    + " upperY=" + UPPER_Y
                    + " upperHeight=" + level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0)
                    + " lowerLight=" + level.getBrightness(LightLayer.BLOCK, LOWER_TEST_POS)
                    + " upperLight=" + level.getBrightness(LightLayer.BLOCK, UPPER_TEST_POS));
            }
        } catch (Throwable t) {
            done = true;
            System.out.println(FAIL_MARKER + " error=" + t);
            t.printStackTrace();
            player.connection.disconnect(Component.literal("Endless extreme-Y integration test failed: " + t));
        }
    }

    private static void prepare(ServerLevel level, ServerPlayer player) {
        require(!level.isOutsideBuildHeight(LOWER_Y), "logical minimum must be buildable");
        require(!level.isOutsideBuildHeight(UPPER_Y), "logical maximum - 1 must be buildable");
        require(level.isOutsideBuildHeight(LOWER_Y - 1), "logical minimum - 1 must be rejected");
        require(level.isOutsideBuildHeight(EndlessLogicalHeights.MAX_BUILD_HEIGHT),
            "logical maximum is exclusive and must be rejected");

        prepareBoundary(level, player, false);
        prepareBoundary(level, player, true);

        lowerStand = spawnStand(level, 10.5D, LOWER_Y + 1.0D);
        upperStand = spawnStand(level, 10.5D, UPPER_Y - 1.0D);
    }

    private static void prepareBoundary(ServerLevel level, ServerPlayer player, boolean upper) {
        int y = upper ? UPPER_Y : LOWER_Y;
        BlockPos glowstone = upper ? UPPER_TEST_POS : LOWER_TEST_POS;
        BlockPos water = upper ? UPPER_WATER_POS : LOWER_WATER_POS;
        BlockPos chest = upper ? UPPER_CHEST_POS : LOWER_CHEST_POS;
        BlockPos poi = upper ? UPPER_POI_POS : LOWER_POI_POS;
        BlockPos power = upper ? UPPER_POWER_POS : LOWER_POWER_POS;
        BlockPos lamp = upper ? UPPER_LAMP_POS : LOWER_LAMP_POS;
        String edge = upper ? "upper" : "lower";

        require(level.setBlock(glowstone, Blocks.GLOWSTONE.defaultBlockState(), 3), edge + " glowstone write failed");
        require(level.setBlock(water, Blocks.WATER.defaultBlockState(), 3), edge + " water write failed");
        require(level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3), edge + " chest write failed");
        // Vanilla HOME POIs include only the HEAD half of beds; the default
        // BedBlock state is FOOT and therefore is intentionally not a POI.
        require(level.setBlock(poi,
            Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 3),
            edge + " bed/POI write failed");
        require(level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3), edge + " redstone source write failed");

        // Exercise the ordinary BlockItem placement path, not only direct world writes.
        placeFromSupport(player, power, Direction.EAST, Blocks.REDSTONE_LAMP);
        require(level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP), edge + " lamp item placement failed");
        require(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " redstone neighbor signal did not reach placed lamp");

        // Prove the first coordinate outside either logical edge remains illegal.
        BlockPos boundarySupport = pos(8, y);
        require(level.setBlock(boundarySupport, Blocks.DEEPSLATE.defaultBlockState(), 3),
            edge + " boundary support write failed");
        BlockPos illegal = upper ? boundarySupport.above() : boundarySupport.below();
        placeFromSupport(player, boundarySupport, upper ? Direction.UP : Direction.DOWN, Blocks.STONE);
        require(level.getBlockState(illegal).isAir(), edge + " placement escaped logical build boundary at " + illegal);

        require(level.getBlockState(glowstone).is(Blocks.GLOWSTONE), edge + " block read mismatch");
        require(level.getFluidState(water).isSource(), edge + " fluid state missing");
        require(level.getBlockState(chest).is(Blocks.CHEST), edge + " chest state missing");
        require(level.getBlockEntity(chest) != null, edge + " block entity was not created");
        require(level.getBrightness(LightLayer.BLOCK, glowstone) >= 15, edge + " glowstone emission missing");
        BlockPos inwardLight = upper ? glowstone.below() : glowstone.above();
        require(level.getBrightness(LightLayer.BLOCK, inwardLight) > 0,
            edge + " block light did not propagate toward the logical interior");

        // Removing power schedules the vanilla lamp's four-tick turn-off.
        require(level.removeBlock(power, false), edge + " redstone source removal failed");
    }

    private static void verifyDelayedMechanics(ServerLevel level) {
        verifyLampScheduledTick(level, LOWER_POWER_POS, LOWER_LAMP_POS, "lower");
        verifyLampScheduledTick(level, UPPER_POWER_POS, UPPER_LAMP_POS, "upper");
    }

    private static void verifyLampScheduledTick(ServerLevel level, BlockPos power, BlockPos lamp, String edge) {
        require(level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP), edge + " lamp disappeared before scheduled tick");
        require(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " scheduled redstone-lamp tick did not run");
        require(level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3),
            edge + " redstone source restore failed");
        require(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " restored neighbor power did not relight lamp");
    }

    private static void flushReloadAndVerify(ServerLevel level) {
        MinecraftVerticalWorld vertical = EndlessVerticalEngine.world(level);
        vertical.flushDirty();

        ChunkPos poiChunk = new ChunkPos(LOWER_POI_POS);
        ExtendedPoiStorage.flush(level, poiChunk);
        ExtendedPoiStorage.unload(level, poiChunk);
        EndlessVerticalEngine.close(level);

        verifyReloadedBoundary(level, false);
        verifyReloadedBoundary(level, true);
        requirePoi(level, LOWER_POI_POS, "lower-bound POI did not survive flush + eviction + reload");
        requirePoi(level, UPPER_POI_POS, "upper-bound POI did not survive flush + eviction + reload");

        require(level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0) == UPPER_Y + 1,
            "sparse heightmap did not include the uppermost legal block");
    }

    private static void verifyReloadedBoundary(ServerLevel level, boolean upper) {
        BlockPos glowstone = upper ? UPPER_TEST_POS : LOWER_TEST_POS;
        BlockPos water = upper ? UPPER_WATER_POS : LOWER_WATER_POS;
        BlockPos chest = upper ? UPPER_CHEST_POS : LOWER_CHEST_POS;
        BlockPos lamp = upper ? UPPER_LAMP_POS : LOWER_LAMP_POS;
        String edge = upper ? "upper" : "lower";

        require(level.getBlockState(glowstone).is(Blocks.GLOWSTONE), edge + " block did not survive sparse reload");
        require(level.getFluidState(water).isSource(), edge + " fluid did not survive sparse reload");
        require(level.getBlockState(chest).is(Blocks.CHEST), edge + " chest state did not survive sparse reload");
        require(level.getBlockEntity(chest) != null, edge + " block entity disappeared after sparse reload");
        require(level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP)
                && level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
            edge + " placed/redstone state did not survive sparse reload");
        require(level.getBrightness(LightLayer.BLOCK, glowstone) >= 15,
            edge + " light rebuild after reload lost glowstone emission");
        BlockPos inwardLight = upper ? glowstone.below() : glowstone.above();
        require(level.getBrightness(LightLayer.BLOCK, inwardLight) > 0,
            edge + " light rebuild after reload lost inward propagation");
    }

    private static void placeFromSupport(ServerPlayer player, BlockPos support, Direction face, ItemLike item) {
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), face, support, false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static ArmorStand spawnStand(ServerLevel level, double x, double y) {
        ArmorStand stand = new ArmorStand(level, x, y, 0.5D);
        stand.setNoGravity(true);
        require(level.addFreshEntity(stand), "could not spawn armor stand at y=" + y);
        return stand;
    }

    private static void requireEntityAlive(ArmorStand stand, String message) {
        require(stand != null && stand.isAlive() && !stand.isRemoved(), message);
    }

    private static void requirePoi(ServerLevel level, BlockPos pos, String message) {
        require(level.getPoiManager().existsAtPosition(PoiTypes.HOME, pos), message + " (direct)");
        require(level.getPoiManager().findClosest(
                holder -> holder.is(PoiTypes.HOME), pos, 16, PoiManager.Occupancy.ANY)
            .filter(pos::equals).isPresent(), message + " (search)");
    }

    private static BlockPos pos(int x, int y) {
        return new BlockPos(x, y, 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
