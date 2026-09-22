package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;

/** Client-side assertions used by tools/live_join_test.py. */
public final class LiveJoinTest {
    public static final int DEFAULT_EXPECTED_MIN_BUILD_HEIGHT = -4096;
    public static final int DEFAULT_EXPECTED_MAX_BUILD_HEIGHT = 4096;

    private static final int EXPECTED_MIN_BUILD_HEIGHT =
        intProperty("endless.liveJoinTest.expectedMin", DEFAULT_EXPECTED_MIN_BUILD_HEIGHT);
    private static final int EXPECTED_MAX_BUILD_HEIGHT =
        intProperty("endless.liveJoinTest.expectedMax", DEFAULT_EXPECTED_MAX_BUILD_HEIGHT);
    private static final boolean EXPECT_LOGICAL =
        Boolean.parseBoolean(System.getProperty("endless.liveJoinTest.expectLogical", "true"));
    private static final boolean HIGH_Y_TEST =
        Boolean.parseBoolean(System.getProperty("endless.liveJoinTest.highY", "false"));
    private static final boolean WAYSTONES_TEST =
        Boolean.parseBoolean(System.getProperty("endless.liveJoinWaystonesTest", "false"));

    public static final String PASS_MARKER = "ENDLESS_LIVE_JOIN_TEST_PASS";
    public static final String FAIL_MARKER = "ENDLESS_LIVE_JOIN_TEST_FAIL";
    public static final String PRE_LOGIN_PASS_MARKER = "ENDLESS_PRE_LOGIN_RANGE_PASS";
    public static final String PRESEED_MARKER = "ENDLESS_LIVE_JOIN_TEST_PRESEEDED_STALE_RANGE";
    public static final String LOWER_EXTREME_PASS_MARKER = "ENDLESS_EXTREME_LOWER_CLIENT_PASS";
    public static final String UPPER_EXTREME_PASS_MARKER = "ENDLESS_EXTREME_UPPER_CLIENT_PASS";

    public static final String SYSTEM_PROPERTY = "endless.liveJoinTest";
    public static final String PRESEED_STALE_PROPERTY = "endless.liveJoinTest.preseedStaleRange";

    private static boolean armed;
    private static boolean staleRangePreseeded;
    private static boolean preLoginChecked;
    private static boolean lowerExtremeSeen;
    private static boolean lowerInteractionDone;
    private static boolean upperInteractionDone;
    private static boolean lowerRenderMarkerPrinted;
    private static boolean upperRenderMarkerPrinted;
    private static int lowerInteractionStage;
    private static int upperInteractionStage;
    private static boolean extremeClientDone;
    private static int ticksWithLevel;

    private LiveJoinTest() {}

    private static int intProperty(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.err.println("Endless: invalid " + key + " value '" + raw + "'; using " + fallback);
            return fallback;
        }
    }

    public static boolean isArmed() {
        if (armed) return true;
        if ("true".equalsIgnoreCase(System.getProperty(SYSTEM_PROPERTY))) armed = true;
        return armed;
    }

    public static void preseedStaleRangeIfRequested() {
        if (!isArmed() || staleRangePreseeded
            || !Boolean.parseBoolean(System.getProperty(PRESEED_STALE_PROPERTY, "false"))) return;
        staleRangePreseeded = true;
        EndlessHeights.applyEffective(DEFAULT_EXPECTED_MIN_BUILD_HEIGHT, DEFAULT_EXPECTED_MAX_BUILD_HEIGHT);
        EndlessLogicalHeights.activate();
        System.out.println(PRESEED_MARKER
            + " min=" + EndlessHeights.getMinBuildHeight()
            + " max=" + EndlessHeights.getMaxBuildHeight());
    }

    public static void assertPreLoginRange() {
        if (!isArmed() || preLoginChecked) return;
        preLoginChecked = true;
        if (Boolean.parseBoolean(System.getProperty(PRESEED_STALE_PROPERTY, "false"))
            && !staleRangePreseeded) {
            fail("stalePreseedMissing", "");
            return;
        }
        int endlessMin = EndlessHeights.getMinBuildHeight();
        int endlessMax = EndlessHeights.getMaxBuildHeight();
        boolean logical = EndlessLogicalHeights.isActive();
        if (endlessMin == EXPECTED_MIN_BUILD_HEIGHT
            && endlessMax == EXPECTED_MAX_BUILD_HEIGHT
            && logical == EXPECT_LOGICAL) {
            System.out.println(PRE_LOGIN_PASS_MARKER
                + " endlessMin=" + endlessMin
                + " endlessMax=" + endlessMax
                + " denseMin=" + EndlessHeights.getDenseMinBuildHeight()
                + " denseMax=" + EndlessHeights.getDenseMaxBuildHeight()
                + " logical=" + logical);
        } else {
            fail("preLogin", " endlessMin=" + endlessMin
                + " endlessMax=" + endlessMax
                + " logical=" + logical
                + " expectedLogical=" + EXPECT_LOGICAL);
        }
    }

    public static boolean tick() {
        if (!isArmed() || !preLoginChecked) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        ticksWithLevel++;

        Level level = mc.level;
        int levelMin = level.getMinY();
        int levelHeight = level.getHeight();
        int endlessMin = EndlessHeights.getMinBuildHeight();
        int endlessMax = EndlessHeights.getMaxBuildHeight();
        int denseMin = EndlessHeights.getDenseMinBuildHeight();
        int denseMax = EndlessHeights.getDenseMaxBuildHeight();
        int[] expectedDense = EndlessHeights.denseRangeForLogical(
            EXPECTED_MIN_BUILD_HEIGHT, EXPECTED_MAX_BUILD_HEIGHT);
        boolean logical = EndlessLogicalHeights.isActive();

        if (levelMin != expectedDense[0]
            || levelHeight != expectedDense[1] - expectedDense[0]
            || denseMin != expectedDense[0]
            || denseMax != expectedDense[1]
            || endlessMin != EXPECTED_MIN_BUILD_HEIGHT
            || endlessMax != EXPECTED_MAX_BUILD_HEIGHT
            || logical != EXPECT_LOGICAL) {
            if (ticksWithLevel >= 40) {
                fail("postJoin", " levelMin=" + levelMin
                    + " levelHeight=" + levelHeight
                    + " endlessMin=" + endlessMin
                    + " endlessMax=" + endlessMax
                    + " denseMin=" + denseMin
                    + " denseMax=" + denseMax
                    + " expectedDenseMin=" + expectedDense[0]
                    + " expectedDenseMax=" + expectedDense[1]
                    + " logical=" + logical
                    + " expectedLogical=" + EXPECT_LOGICAL);
                mc.stop();
                return true;
            }
            return false;
        }

        if (HIGH_Y_TEST) {
            return tickExtremeTest(mc, level, levelMin, levelHeight, endlessMin, endlessMax, denseMin, denseMax, logical);
        }

        if (ticksWithLevel < 40) return false;
        pass(levelMin, levelHeight, endlessMin, endlessMax, denseMin, denseMax, logical);
        mc.stop();
        return true;
    }

    private static boolean tickExtremeTest(
        Minecraft mc,
        Level level,
        int levelMin,
        int levelHeight,
        int endlessMin,
        int endlessMax,
        int denseMin,
        int denseMax,
        boolean logical
    ) {
        if (extremeClientDone) return false;
        double playerY = mc.player == null ? Double.NaN : mc.player.getY();
        boolean atLower = mc.player != null
            && Math.abs(playerY - LiveHighYServerTest.lowerPlayerY()) < 8.0D;
        boolean atUpper = mc.player != null
            && Math.abs(playerY - LiveHighYServerTest.upperPlayerY()) < 8.0D;

        if (ticksWithLevel % 100 == 0) {
            System.out.println("ENDLESS_CLIENT_PROGRESS ticks=" + ticksWithLevel + " y=" + playerY
                + " lowerStage=" + lowerInteractionStage + " upperStage=" + upperInteractionStage
                + " lower=" + boundaryStatus(level, false) + " upper=" + boundaryStatus(level, true));
        }

        // Once the lower break has been issued (stage 2), keep consuming its
        // prediction acknowledgement even if the authoritative server has
        // already moved the player to the upper fixture. Under a slow server
        // the teleport can beat the client tick that observes the second ack.
        boolean lowerAwaitingAck = lowerInteractionStage == 2;
        if ((atLower || lowerAwaitingAck) && !lowerExtremeSeen) {
            lowerInteractionDone = driveClientInteraction(mc, level, false, lowerInteractionDone);
            BoundaryStatus lower = boundaryStatus(level, false);
            printRenderMarkerOnce(lower, false);
            if (lower.ok() && lowerInteractionDone) {
                lowerExtremeSeen = true;
                System.out.println(LOWER_EXTREME_PASS_MARKER
                    + " playerY=" + playerY
                    + " blockY=" + LiveHighYServerTest.lowerY()
                    + " light=" + lower.sourceLight
                    + " render=true prediction=true");
            }
        }

        if (atUpper) {
            upperInteractionDone = driveClientInteraction(mc, level, true, upperInteractionDone);
            BoundaryStatus upper = boundaryStatus(level, true);
            printRenderMarkerOnce(upper, true);
            boolean waystones = !WAYSTONES_TEST || waystoneStatus(level);
            if (lowerExtremeSeen && upperInteractionDone && upper.ok() && waystones) {
                System.out.println(UPPER_EXTREME_PASS_MARKER
                    + " playerY=" + playerY
                    + " blockY=" + LiveHighYServerTest.upperY()
                    + " height=" + level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0)
                    + " light=" + upper.sourceLight
                    + " waystones=" + waystones
                    + " render=true prediction=true");
                pass(levelMin, levelHeight, endlessMin, endlessMax, denseMin, denseMax, logical);
                // Keep the real client connected after its local assertions pass.
                // The harness still has required server-side mechanics/persistence
                // markers to collect; disconnecting here can race those checks.
                extremeClientDone = true;
                return true;
            }
        }

        if (ticksWithLevel < 1600) return false;
        BoundaryStatus lower = boundaryStatus(level, false);
        BoundaryStatus upper = boundaryStatus(level, true);
        fail("extremeY", " lowerSeen=" + lowerExtremeSeen
            + " atLower=" + atLower
            + " atUpper=" + atUpper
            + " playerY=" + (mc.player == null ? "null" : mc.player.getY())
            + " lower=" + lower
            + " upper=" + upper
            + " waystones=" + (!WAYSTONES_TEST || waystoneStatus(level)));
        mc.stop();
        return true;
    }

    private static boolean driveClientInteraction(Minecraft mc, Level level, boolean upper, boolean done) {
        if (done || mc.player == null || mc.gameMode == null) return done;
        BlockPos support = upper ? LiveHighYServerTest.upperInteractionSupportPos() : LiveHighYServerTest.lowerInteractionSupportPos();
        BlockPos target = upper ? LiveHighYServerTest.upperInteractionTargetPos() : LiveHighYServerTest.lowerInteractionTargetPos();
        BlockPos alias = LiveHighYServerTest.packedAliasPos(target);
        int stage = upper ? upperInteractionStage : lowerInteractionStage;
        // At the full +/-8M envelope the 12-bit packed-Y alias can itself be
        // sparse and hundreds of blocks outside the client's current page window.
        // Only require client visibility when the alias is in the always-sent dense
        // core; the authoritative server test always checks that the alias remains
        // the gold canary before and after the real client interaction.
        boolean clientAliasVisible = !EndlessHeights.isOutsideDenseBuildHeight(alias.getY());

        if (clientAliasVisible && !level.getBlockState(alias).is(Blocks.GOLD_BLOCK)) {
            return false;
        }
        if (stage == 0) {
            if (!level.getBlockState(support).is(Blocks.DEEPSLATE) || !level.getBlockState(target).isAir()
                || !mc.player.getMainHandItem().is(Blocks.STONE.asItem())) {
                return false;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            if (upper) upperInteractionStage = 1; else lowerInteractionStage = 1;
            return false;
        }
        if (stage == 1) {
            if (LivePredictionProbe.count(target) < 1) return false;
            if (!level.getBlockState(target).is(Blocks.STONE)) {
                fail("placementRejected", " target=" + target + " state=" + level.getBlockState(target));
                mc.stop();
                return false;
            }
            if (clientAliasVisible && !level.getBlockState(alias).is(Blocks.GOLD_BLOCK)) return false;
            mc.gameMode.startDestroyBlock(target, Direction.UP);
            if (upper) upperInteractionStage = 2; else lowerInteractionStage = 2;
            return false;
        }
        if (stage == 2 && LivePredictionProbe.count(target) >= 2
            && level.getBlockState(target).isAir()
            && (!clientAliasVisible || level.getBlockState(alias).is(Blocks.GOLD_BLOCK))) {
            System.out.println("ENDLESS_CLIENT_PREDICTION_PASS edge=" + (upper ? "upper" : "lower")
                + " target=" + target + " alias=" + alias
                + " clientAliasVisible=" + clientAliasVisible + " acknowledged=true");
            if (!upper) return true;
            upperInteractionStage = 3;
            return false;
        }
        if (stage == 2 && LivePredictionProbe.count(target) >= 2 && !level.getBlockState(target).isAir()) {
            fail("breakingRejected", " target=" + target + " state=" + level.getBlockState(target));
            mc.stop();
            return false;
        }
        if (upper && stage == 3) {
            BlockPos persistentSupport = LiveHighYServerTest.upperPersistentSupportPos();
            BlockPos persistentTarget = LiveHighYServerTest.upperPersistentTargetPos();
            if (!level.getBlockState(persistentSupport).is(Blocks.DEEPSLATE)
                || !level.getBlockState(persistentTarget).isAir()
                || !mc.player.getMainHandItem().is(Blocks.STONE.asItem())) {
                return false;
            }
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(persistentSupport), Direction.UP, persistentSupport, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            upperInteractionStage = 4;
            return false;
        }
        if (upper && stage == 4) {
            BlockPos persistentTarget = LiveHighYServerTest.upperPersistentTargetPos();
            if (LivePredictionProbe.count(persistentTarget) < 1) return false;
            if (!level.getBlockState(persistentTarget).is(Blocks.STONE)) {
                fail("persistentPlacementRejected", " target=" + persistentTarget
                    + " state=" + level.getBlockState(persistentTarget));
                mc.stop();
                return false;
            }
            System.out.println("ENDLESS_CLIENT_PERSISTENT_PLACEMENT_PASS target=" + persistentTarget
                + " acknowledged=true");
            return true;
        }
        return false;
    }

    private static void printRenderMarkerOnce(BoundaryStatus status, boolean upper) {
        if (!status.viewArea || !status.renderChunk || !status.renderGraph) return;
        if (upper ? upperRenderMarkerPrinted : lowerRenderMarkerPrinted) return;
        if (upper) upperRenderMarkerPrinted = true; else lowerRenderMarkerPrinted = true;
        System.out.println("ENDLESS_RENDER_PATH_PASS edge=" + (upper ? "upper" : "lower")
            + " viewArea=true renderChunk=true renderGraph=true");
    }

    private static BoundaryStatus boundaryStatus(Level level, boolean upper) {
        BlockPos glowstone = upper ? LiveHighYServerTest.upperTestPos() : LiveHighYServerTest.lowerTestPos();
        BlockPos water = upper ? LiveHighYServerTest.upperWaterPos() : LiveHighYServerTest.lowerWaterPos();
        BlockPos chest = upper ? LiveHighYServerTest.upperChestPos() : LiveHighYServerTest.lowerChestPos();
        BlockPos lamp = upper ? LiveHighYServerTest.upperLampPos() : LiveHighYServerTest.lowerLampPos();
        BlockPos inward = upper ? glowstone.below() : glowstone.above();
        int outsideY = upper ? EndlessHeights.getMaxBuildHeight() : EndlessHeights.getMinBuildHeight() - 1;

        boolean buildable = !level.isOutsideBuildHeight(glowstone);
        boolean outsideRejected = level.isOutsideBuildHeight(outsideY);
        boolean block = level.getBlockState(glowstone).is(Blocks.GLOWSTONE);
        boolean fluid = level.getFluidState(water).isSource();
        boolean blockEntity = level.getBlockState(chest).is(Blocks.CHEST)
            && level.getBlockEntity(chest) != null;
        boolean placedLamp = level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP)
            && level.getBlockState(lamp).getValue(BlockStateProperties.LIT);
        int sourceLight = level.getBrightness(LightLayer.BLOCK, glowstone);
        int inwardLight = level.getBrightness(LightLayer.BLOCK, inward);
        boolean height = !upper || level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0)
            == LiveHighYServerTest.upperY() + 1;
        boolean render = canRender(level, glowstone);
        boolean viewArea = LiveRenderProbe.sawViewArea(glowstone);
        boolean renderChunk = LiveRenderProbe.sawRenderChunk(glowstone);
        boolean renderGraph = LiveRenderProbe.sawRenderGraph(glowstone);

        return new BoundaryStatus(
            buildable, outsideRejected, block, fluid, blockEntity, placedLamp,
            sourceLight, inwardLight, height, render, viewArea, renderChunk, renderGraph
        );
    }

    private static boolean waystoneStatus(Level level) {
        try {
            Block waystone = BuiltInRegistries.BLOCK.get(Identifier.fromNamespaceAndPath("waystones", "waystone"))
                .map(holder -> holder.value()).orElse(Blocks.AIR);
            BlockPos base = LiveHighYServerTest.upperWaystoneBasePos();
            BlockPos top = LiveHighYServerTest.upperWaystoneTopPos();
            return waystone != Blocks.AIR
                && level.getBlockState(base).is(waystone)
                && level.getBlockState(top).is(waystone)
                && level.getBlockEntity(base) != null;
        } catch (Throwable t) {
            System.out.println("ENDLESS_WAYSTONES_CLIENT_FAIL error=" + t);
            return false;
        }
    }

    private static boolean canRender(Level level, BlockPos pos) {
        try {
            if (!(level instanceof ClientLevel clientLevel)) return false;
            RenderSectionRegion region = new RenderRegionCache().createRegion(clientLevel, SectionPos.asLong(pos));
            return region != null && region.getBlockState(pos).is(Blocks.GLOWSTONE);
        } catch (Throwable t) {
            System.out.println("ENDLESS_EXTREME_RENDER_FAIL pos=" + pos + " error=" + t);
            return false;
        }
    }

    private static void pass(
        int levelMin,
        int levelHeight,
        int endlessMin,
        int endlessMax,
        int denseMin,
        int denseMax,
        boolean logical
    ) {
        System.out.println(PASS_MARKER
            + " levelMin=" + levelMin
            + " levelMax=" + (levelMin + levelHeight)
            + " levelHeight=" + levelHeight
            + " endlessMin=" + endlessMin
            + " endlessMax=" + endlessMax
            + " denseMin=" + denseMin
            + " denseMax=" + denseMax
            + " logical=" + logical);
    }

    private static void fail(String phase, String details) {
        System.out.println(FAIL_MARKER
            + " phase=" + phase
            + details
            + " expectedMin=" + EXPECTED_MIN_BUILD_HEIGHT
            + " expectedMax=" + EXPECTED_MAX_BUILD_HEIGHT);
    }

    private record BoundaryStatus(
        boolean buildable,
        boolean outsideRejected,
        boolean block,
        boolean fluid,
        boolean blockEntity,
        boolean placedLamp,
        int sourceLight,
        int inwardLight,
        boolean height,
        boolean render,
        boolean viewArea,
        boolean renderChunk,
        boolean renderGraph
    ) {
        boolean ok() {
            return buildable && outsideRejected && block && fluid && blockEntity && placedLamp
                && sourceLight >= 15 && inwardLight > 0 && height && render && viewArea && renderChunk && renderGraph;
        }
    }
}
