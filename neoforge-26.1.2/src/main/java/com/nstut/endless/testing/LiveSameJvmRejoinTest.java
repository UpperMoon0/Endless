package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import net.minecraft.client.Minecraft;
import com.nstut.endless.mixin.accessor.VisibleSectionsAccessor;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact regression for the integrated-server lifecycle that originally lost a
 * sparse client page after Save & Quit -> reopen in the same Minecraft JVM.
 */
public final class LiveSameJvmRejoinTest {
    public static final String SYSTEM_PROPERTY = "endless.sameJvmRejoinTest";
    public static final String PASS_MARKER = "ENDLESS_SAME_JVM_REJOIN_PASS";
    public static final String FAIL_MARKER = "ENDLESS_SAME_JVM_REJOIN_FAIL";
    public static final String PLACEMENT_MARKER = "ENDLESS_SAME_JVM_FIRST_PLACEMENT_PASS";
    public static final String SAVE_MARKER = "ENDLESS_SAME_JVM_FIRST_SAVE_PASS";

    private static final String WORLD_ID = "endless-same-jvm-rejoin";
    private static final int TARGET_Y = Integer.getInteger("endless.sameJvmRejoinTest.targetY", 1_000_000);
    private static final boolean LEGACY_LAYOUT = Boolean.parseBoolean(
        System.getProperty("endless.sameJvmRejoinTest.legacyLayout", "true"));
    private static final BlockPos SUPPORT = new BlockPos(0, TARGET_Y - 1, 0);
    private static final BlockPos TARGET = SUPPORT.above();
    private static final List<BlockEntityFixture> BLOCK_ENTITY_FIXTURES = List.of(
        new BlockEntityFixture(new BlockPos(2, TARGET_Y, 0), Blocks.CHEST.defaultBlockState()),
        new BlockEntityFixture(new BlockPos(4, TARGET_Y, 0), Blocks.ENDER_CHEST.defaultBlockState()),
        new BlockEntityFixture(new BlockPos(6, TARGET_Y, 0), Blocks.WHITE_SHULKER_BOX.defaultBlockState())
    );
    private static final int MAX_TICKS = 4_000;
    private static final int SAVE_COMPLETION_TIMEOUT_TICKS = 1_200;
    private static final int HYSTERESIS_PROBE_SECTION_DELTA = 8;
    private static final int DENSE_CANARY_Y = 0;
    private static final double GROUND_RETURN_Y = 100.0D;
    private static final BlockPos DENSE_RENDER_POS = new BlockPos(0, 80, 0);

    private static final AtomicReference<String> ASYNC_FAILURE = new AtomicReference<>();

    private static boolean armed;
    private static boolean done;
    private static Stage stage = Stage.CREATE_WORLD;
    private static int ticks;
    private static int stageTick;
    private static boolean bootstrapSaveQueued;
    private static volatile boolean bootstrapSaveComplete;
    private static boolean bootstrapStopRequested;
    private static MinecraftServer bootstrapStoppingServer;
    private static boolean bootstrapClearIssued;
    private static boolean bootstrapReopenIssued;
    private static boolean fixtureTaskQueued;
    private static volatile boolean fixtureReady;
    private static boolean fixtureBlocksQueued;
    private static volatile boolean fixtureBlocksReady;
    private static int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private static int placementAttempts;
    private static boolean placementCheckQueued;
    private static volatile boolean serverSawPlacement;
    private static boolean saveTaskQueued;
    private static volatile boolean saveComplete;
    private static boolean serverStopRequested;
    private static MinecraftServer stoppingServer;
    private static boolean clearIssued;
    private static boolean reopenIssued;
    private static boolean rejoinCheckQueued;
    private static volatile boolean reopenedServerSawBlock;
    private static boolean hysteresisTeleportQueued;
    private static volatile boolean hysteresisTeleportServerDone;
    private static int initialViewBaseSection = Integer.MIN_VALUE;
    private static boolean groundTeleportQueued;
    private static volatile boolean groundTeleportServerDone;
    private static UUID playerUuid;
    private static BlockState[] denseCanary;

    private LiveSameJvmRejoinTest() {}

    public static boolean isArmed() {
        if (armed) return true;
        if ("true".equalsIgnoreCase(System.getProperty(SYSTEM_PROPERTY))) armed = true;
        return armed;
    }

    public static void tick() {
        if (!isArmed() || done) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;

        String asyncFailure = ASYNC_FAILURE.get();
        if (asyncFailure != null) {
            fail(mc, "async", " error=" + asyncFailure);
            return;
        }
        if (ticks > MAX_TICKS) {
            fail(mc, "globalTimeout", " stage=" + stage + " ticks=" + ticks);
            return;
        }

        try {
            switch (stage) {
                case CREATE_WORLD -> createWorld(mc);
                case WAIT_BOOTSTRAP_JOIN -> waitForBootstrapJoin(mc);
                case WAIT_BOOTSTRAP_CLOSED -> waitForBootstrapClosedAndMigrate(mc);
                case WAIT_FIRST_JOIN -> waitForFirstJoin(mc);
                case WAIT_FIXTURE -> waitForFixture(mc);
                case WAIT_PLACEMENT -> waitForPlacement(mc);
                case WAIT_SAVE -> waitForSave(mc);
                case WAIT_CLOSED -> waitForClosedAndReopen(mc);
                case WAIT_SECOND_JOIN -> waitForSecondJoin(mc);
                case WAIT_CLIENT_RESYNC -> waitForClientResync(mc);
                case WAIT_HYSTERESIS_PROBE -> waitForHysteresisProbe(mc);
                case WAIT_GROUND_RETURN -> waitForGroundReturn(mc);
                case DONE -> { }
            }
        } catch (Throwable t) {
            fail(mc, "clientException", " error=" + t);
            t.printStackTrace();
        }
    }

    private static void createWorld(Minecraft mc) {
        if (mc.level != null || mc.hasSingleplayerServer()) return;
        // Fabric may begin client ticks before the initial model reload has
        // applied ModelManager.modelGroups. Opening a world before that point
        // lets ordinary vanilla chunk packets call LevelRenderer#setBlockDirty
        // against an uninitialized model manager. Gate world creation itself,
        // not merely fixture edits after the join.
        if (!clientModelsReady(mc)) {
            requireStageWithin(mc, 1_200, "client model manager did not finish before world creation");
            return;
        }
        LevelSettings settings = new LevelSettings(
            "Endless Same-JVM Rejoin",
            GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
            true,
            WorldDataConfiguration.DEFAULT
        );
        setStage(Stage.WAIT_BOOTSTRAP_JOIN);
        mc.createWorldOpenFlows().createFreshLevel(
            WORLD_ID,
            settings,
            new WorldOptions(0x5EEDL, true, false),
            WorldPresets::createNormalWorldDimensions,
            mc.screen
        );
    }

    private static void waitForBootstrapJoin(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            requireStageWithin(mc, 1_200, "bootstrap integrated server did not open");
            return;
        }
        requireExpectedRange(mc, "bootstrapJoin");
        if (!clientModelsReady(mc)) {
            requireStageWithin(mc, 1_200, "bootstrap client model manager did not finish initial reload");
            return;
        }
        playerUuid = mc.player.getUUID();
        if (!bootstrapSaveQueued) {
            bootstrapSaveQueued = true;
            serverTask(server, "bootstrapSave", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                ServerLevel level = player.level();
                // Force the target horizontal chunk to be real generated terrain before
                // converting the save metadata to a legacy wide dense layout.
                level.getChunk(0, 0);
                denseCanary = captureDenseCanary(level);
                require(level.getSectionsCount() == 24,
                    "bootstrap world must start from the fresh v0.5 vanilla dense core, got " + level.getSectionsCount());
                server.saveEverything(false, true, true);
                bootstrapSaveComplete = true;
            });
        }
        if (!bootstrapSaveComplete) {
            requireStageWithin(mc, SAVE_COMPLETION_TIMEOUT_TICKS, "bootstrap terrain save did not complete");
            return;
        }
        if (!LEGACY_LAYOUT) {
            setStage(Stage.WAIT_FIRST_JOIN);
            return;
        }
        if (!bootstrapStopRequested) {
            bootstrapStopRequested = true;
            bootstrapStoppingServer = server;
            server.halt(false);
            setStage(Stage.WAIT_BOOTSTRAP_CLOSED);
        }
    }

    private static void waitForBootstrapClosedAndMigrate(Minecraft mc) {
        if (!bootstrapClearIssued) {
            if (bootstrapStoppingServer != null && !bootstrapStoppingServer.isShutdown()) {
                requireStageWithin(mc, 1_000, "bootstrap integrated server did not stop");
                return;
            }
            bootstrapClearIssued = true;
            mc.disconnectWithProgressScreen();
            return;
        }
        if (mc.level != null || mc.hasSingleplayerServer()) {
            requireStageWithin(mc, 1_000, "bootstrap world did not fully clear");
            return;
        }
        if (!bootstrapReopenIssued) {
            if (!clientModelsReady(mc)) {
                requireStageWithin(mc, 1_200, "client model manager became unavailable before legacy-layout reopen");
                return;
            }
            writeLegacyDenseRangeMetadata(mc);
            bootstrapReopenIssued = true;
            setStage(Stage.WAIT_FIRST_JOIN);
            mc.createWorldOpenFlows().openWorld(WORLD_ID, () -> {});
        }
    }

    private static void writeLegacyDenseRangeMetadata(Minecraft mc) {
        try {
            Path dataDir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD_ID).resolve("data");
            Files.createDirectories(dataDir);
            CompoundTag data = new CompoundTag();
            data.putInt("MinBuildHeight", -2032);
            data.putInt("MaxBuildHeight", 2032);
            CompoundTag root = new CompoundTag();
            root.put("data", data);
            NbtIo.writeCompressed(root, dataDir.resolve("endless_build_heights.dat"));
            System.out.println("ENDLESS_SAME_JVM_LEGACY_LAYOUT_FIXTURE denseMin=-2032 denseMax=2032");
        } catch (IOException e) {
            throw new IllegalStateException("could not write legacy dense-range fixture", e);
        }
    }

    private static void waitForFirstJoin(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            requireStageWithin(mc, 1_200, "first integrated server did not open");
            return;
        }
        requireExpectedRange(mc, "firstJoin");
        // Fabric can finish the integrated-server join before the initial model
        // reload has applied ModelManager.modelGroups. Do not send fixture block
        // updates until vanilla LevelRenderer can safely evaluate dirty states.
        if (!clientModelsReady(mc)) {
            requireStageWithin(mc, 1_200, "client model manager did not finish initial reload");
            return;
        }
        playerUuid = mc.player.getUUID();
        if (!fixtureTaskQueued) {
            fixtureTaskQueued = true;
            serverTask(server, "prepareFixture", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                ServerLevel level = player.level();
                require(!level.isOutsideBuildHeight(TARGET_Y), "target is outside logical build range");
                int expectedDenseSections = LEGACY_LAYOUT ? 254 : 24;
                require(level.getSectionsCount() == expectedDenseSections,
                    "unexpected dense core section count: expected=" + expectedDenseSections
                        + " actual=" + level.getSectionsCount() + " legacy=" + LEGACY_LAYOUT);
                require(level.getChunk(0, 0).getSections().length == expectedDenseSections,
                    "target chunk dense section count mismatch: expected=" + expectedDenseSections
                        + " actual=" + level.getChunk(0, 0).getSections().length + " legacy=" + LEGACY_LAYOUT);
                verifyDenseCanary(level, LEGACY_LAYOUT ? "migrated pre-placement" : "fresh pre-placement");
                require(EndlessHeights.isOutsideDenseBuildHeight(TARGET_Y),
                    "target must exercise sparse storage, not the dense core");
                // Enter the target sparse page before creating the fixture. Sparse
                // block updates are intentionally scoped to players whose current
                // page window can see them; placing first would test that filter,
                // not post-rejoin synchronization.
                player.setGameMode(GameType.CREATIVE);
                player.setNoGravity(true);
                player.teleportTo(0.5D, TARGET_Y + 2.0D, 0.5D);
                fixtureReady = true;
            });
        }
        setStage(Stage.WAIT_FIXTURE);
    }

    private static void waitForFixture(Minecraft mc) {
        if (!fixtureReady || mc.level == null || mc.player == null || mc.gameMode == null) {
            requireStageWithin(mc, 1_000, "first sparse fixture was not prepared");
            return;
        }
        boolean playerReady = Math.abs(mc.player.getY() - (TARGET_Y + 2.0D)) < 8.0D;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "fixture", " integrated server disappeared before sparse fixture placement");
            return;
        }
        if (playerReady && !fixtureBlocksQueued) {
            fixtureBlocksQueued = true;
            serverTask(server, "placeSparseFixture", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                ServerLevel level = player.level();
                require(level.setBlock(SUPPORT, Blocks.DEEPSLATE.defaultBlockState(), 3),
                    "could not create sparse placement support");
                require(level.removeBlock(TARGET, false) || level.getBlockState(TARGET).isAir(),
                    "could not clear sparse placement target");
                for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
                    require(level.setBlock(fixture.pos(), fixture.state(), 3),
                        "could not create sparse block-entity fixture at " + fixture.pos());
                    requireValidBlockEntity(level, fixture, "initial server fixture");
                }
                // Initial fixture delivery is setup, not the behavior under test.
                // A teleport can move the server player into the target page before
                // vanilla horizontal chunk tracking has admitted chunk 0,0, so the
                // immediate sparse blockChanged packet may legitimately have no
                // recipients. Explicitly send the authoritative current window once
                // the fixture exists; the regression begins at save/reopen below.
                // Test setup must not depend on horizontal chunk-tracker timing.
                // The target fixture is in chunk 0,0, so send that exact loaded
                // chunk's sparse pages after the client has acknowledged the teleport.
                VerticalNetworkBridge.sendVisiblePagesForChunk(player, level.getChunk(0, 0));
                fixtureBlocksReady = true;
            });
            return;
        }
        boolean supportVisible = mc.level.getBlockState(SUPPORT).is(Blocks.DEEPSLATE);
        boolean targetClear = mc.level.getBlockState(TARGET).isAir();
        boolean blockEntitiesReady = blockEntitiesPresent(mc.level);
        if (!playerReady || !fixtureBlocksReady || !supportVisible || !targetClear || !blockEntitiesReady) {
            requireStageWithin(mc, 1_000,
                "client never received first sparse fixture playerY=" + mc.player.getY()
                    + " support=" + mc.level.getBlockState(SUPPORT)
                    + " target=" + mc.level.getBlockState(TARGET)
                    + " blockEntities=" + blockEntityDiagnostic(mc.level));
            return;
        }
        if (!placementCheckQueued) {
            placementCheckQueued = true;
            serverTask(server, "applySparseEdit", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                require(player.level().setBlock(TARGET, Blocks.STONE.defaultBlockState(), 3),
                    "server could not apply sparse million-height edit");
                require(player.level().getBlockState(TARGET).is(Blocks.STONE),
                    "server sparse edit did not settle");
                verifyDenseCanary(player.level(), "after sparse edit");
                serverSawPlacement = true;
            });
        }
        setStage(Stage.WAIT_PLACEMENT);
    }

    private static void waitForPlacement(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            requireStageWithin(mc, 600, "client disconnected before sparse edit acknowledgement");
            return;
        }
        if (!serverSawPlacement) {
            requireStageWithin(mc, 600, "server sparse edit did not complete");
            return;
        }
        if (!mc.level.getBlockState(TARGET).is(Blocks.STONE)) {
            requireStageWithin(mc, 600,
                "client did not receive sparse edit state=" + mc.level.getBlockState(TARGET));
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "placement", " integrated server disappeared");
            return;
        }
        System.out.println(PLACEMENT_MARKER + " target=" + TARGET + " mode=serverSparseEdit server=true clientSynced=true");
        if (!saveTaskQueued) {
            saveTaskQueued = true;
            serverTask(server, "saveWorld", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                require(player.level().getBlockState(TARGET).is(Blocks.STONE),
                    "sparse block vanished before save");
                for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
                    requireValidBlockEntity(player.level(), fixture, "server before save");
                }
                server.saveEverything(false, true, true);
                saveComplete = true;
            });
        }
        setStage(Stage.WAIT_SAVE);
    }

    private static void attemptPlacement(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.gameMode == null) return;
        if (!mc.level.getBlockState(SUPPORT).is(Blocks.DEEPSLATE)) return;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(SUPPORT), Direction.UP, SUPPORT, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        lastPlacementAttemptTick = ticks;
        placementAttempts++;
    }
    private static void waitForSave(Minecraft mc) {
        if (!saveComplete) {
            requireStageWithin(mc, SAVE_COMPLETION_TIMEOUT_TICKS, "integrated server save did not complete");
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "save", " integrated server disappeared before shutdown");
            return;
        }
        if (!serverStopRequested) {
            serverStopRequested = true;
            stoppingServer = server;
            System.out.println(SAVE_MARKER + " target=" + TARGET + " flushed=true");
            // Request the same integrated-server shutdown that Save & Quit drives,
            // but do it outside Minecraft.clearLevel() so the automated tick hook
            // cannot deadlock while clearLevel waits for the server thread.
            server.halt(false);
            setStage(Stage.WAIT_CLOSED);
        }
    }

    private static void waitForClosedAndReopen(Minecraft mc) {
        if (!clearIssued) {
            // Fabric can clear Minecraft#singleplayerServer as soon as the local
            // connection closes, while the old server thread is still flushing
            // chunks and still owns session.lock. Wait on the exact instance we
            // halted so reopen cannot race world-storage teardown.
            if (stoppingServer != null && !stoppingServer.isShutdown()) {
                requireStageWithin(mc, 1_000, "first integrated server did not stop after save");
                return;
            }
            clearIssued = true;
            mc.disconnectWithProgressScreen();
            return;
        }
        if (mc.level != null || mc.hasSingleplayerServer()) {
            requireStageWithin(mc, 1_000, "first integrated server did not fully clear from the client");
            return;
        }
        if (!reopenIssued) {
            if (!clientModelsReady(mc)) {
                requireStageWithin(mc, 1_200, "client model manager became unavailable before same-JVM reopen");
                return;
            }
            LiveRenderProbe.resetWorldEvidence();
            reopenIssued = true;
            setStage(Stage.WAIT_SECOND_JOIN);
            mc.createWorldOpenFlows().openWorld(WORLD_ID, () -> {});
        }
    }

    private static void waitForSecondJoin(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) {
            requireStageWithin(mc, 1_200, "saved world did not reopen in the same client JVM");
            return;
        }
        requireExpectedRange(mc, "secondJoin");
        UUID secondUuid = mc.player.getUUID();
        require(secondUuid.equals(playerUuid), "reopened player UUID changed");
        if (!rejoinCheckQueued) {
            rejoinCheckQueued = true;
            serverTask(server, "verifyReopenedServerState", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                require(player.level().getBlockState(TARGET).is(Blocks.STONE),
                    "saved sparse block is missing on reopened integrated server");
                for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
                    requireValidBlockEntity(player.level(), fixture, "server reopen");
                }
                verifyDenseCanary(player.level(), "server reopen");
                require(Math.abs(player.getY() - (TARGET_Y + 2.0D)) < 32.0D,
                    "reopened player did not retain million-height position: y=" + player.getY());
                player.setNoGravity(true);
                reopenedServerSawBlock = true;
            });
        }
        setStage(Stage.WAIT_CLIENT_RESYNC);
    }

    private static void waitForClientResync(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            requireStageWithin(mc, 1_200, "client disconnected during same-JVM sparse resync");
            return;
        }
        if (!reopenedServerSawBlock) {
            requireStageWithin(mc, 1_200, "reopened server never verified saved sparse state");
            return;
        }
        boolean playerAtTarget = Math.abs(mc.player.getY() - (TARGET_Y + 2.0D)) < 32.0D;
        boolean clientHasBlock = mc.level.getBlockState(TARGET).is(Blocks.STONE);
        boolean clientBlockEntitiesPresent = blockEntitiesPresent(mc.level);
        boolean clientBlockEntitiesCompiled = blockEntitiesCompiled();
        boolean clientBlockEntitiesScannerDiscovered = blockEntitiesScannerDiscovered();
        boolean clientBlockEntitiesInternallyValid = blockEntitiesInternallyValid(mc.level);
        boolean clientBlockEntitiesVisible = blockEntitiesVisible(mc);
        boolean clientDensePreserved = denseCanaryMatches(mc.level);
        LiveRenderProbe.RenderWindowAlignment alignment = LiveRenderProbe.latestRenderWindowAlignment();
        boolean renderWindowAligned = renderWindowAlignmentMatchesCamera(mc, alignment);
        mc.player.setXRot(90.0F);
        // SUPPORT is in the next section below TARGET and has no block entities,
        // so a special block-entity rebuild cannot satisfy this rendering check.
        boolean visibleStoneMesh = hasVisibleSolidMesh(mc, TARGET) && hasVisibleSolidMesh(mc, SUPPORT);
        if (!playerAtTarget || !clientHasBlock || !clientBlockEntitiesPresent
            || !clientBlockEntitiesCompiled || !clientBlockEntitiesScannerDiscovered
            || !clientBlockEntitiesInternallyValid || !clientBlockEntitiesVisible
            || !clientDensePreserved || !visibleStoneMesh || !renderWindowAligned) {
            requireStageWithin(mc, 1_200,
                "saved sparse page was not resynchronized after same-JVM reopen"
                    + " playerY=" + mc.player.getY()
                    + " clientState=" + mc.level.getBlockState(TARGET)
                    + " blockEntities=" + blockEntityDiagnostic(mc.level)
                    + " visibleStoneMesh=" + visibleStoneMesh
                    + " blockEntityCompiled=" + clientBlockEntitiesCompiled
                    + " blockEntityScannerDiscovered=" + clientBlockEntitiesScannerDiscovered
                    + " blockEntityInternallyValid=" + clientBlockEntitiesInternallyValid
                    + " blockEntityVisible=" + clientBlockEntitiesVisible
                    + " renderWindow=" + alignment
                    + " logical=" + EndlessLogicalHeights.isActive() + " densePreserved=" + clientDensePreserved + " dense=" + denseCanaryDiagnostic(mc));
            return;
        }
        System.out.println("ENDLESS_VISIBLE_STONE_MESH_PASS target=" + TARGET + " visible=true solidDraw=true");
        initialViewBaseSection = alignment.viewBaseSection();
        System.out.println("ENDLESS_RENDER_WINDOW_EDGE_PASS phase=initial camera=" + alignment.cameraSection()
            + " lowerEdge=" + alignment.viewBaseSection()
            + " upperEdge=" + alignment.viewMaxSectionInclusive()
            + " tree=[" + alignment.treeBaseSection() + "," + alignment.treeMaxSectionInclusive() + "]");

        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "hysteresisProbe", " integrated server disappeared before render-window probe");
            return;
        }
        if (!hysteresisTeleportQueued) {
            hysteresisTeleportQueued = true;
            serverTask(server, "hysteresisProbe", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                int initialCameraSection = Math.floorDiv(TARGET_Y + 2, 16);
                double probeY = (double) (initialCameraSection + HYSTERESIS_PROBE_SECTION_DELTA) * 16.0D + 2.0D;
                player.teleportTo(0.5D, probeY, 0.5D);
                player.setNoGravity(true);
                hysteresisTeleportServerDone = true;
            });
        }
        setStage(Stage.WAIT_HYSTERESIS_PROBE);
    }

    private static void waitForHysteresisProbe(Minecraft mc) {
        if (mc.level == null || mc.player == null || !hysteresisTeleportServerDone) {
            requireStageWithin(mc, 1_200, "client/server did not complete visibility-tree hysteresis setup");
            return;
        }

        int expectedCameraSection = Math.floorDiv(TARGET_Y + 2, 16) + HYSTERESIS_PROBE_SECTION_DELTA;
        boolean atProbe = cameraSection(mc) == expectedCameraSection;
        LiveRenderProbe.RenderWindowAlignment alignment = LiveRenderProbe.latestRenderWindowAlignment();
        boolean currentAlignment = renderWindowAlignmentMatchesCamera(mc, alignment);
        boolean hysteresisHeldBase = alignment != null
            && alignment.viewBaseSection() == initialViewBaseSection;
        if (!atProbe || !currentAlignment || !hysteresisHeldBase) {
            requireStageWithin(mc, 1_200,
                "visibility tree did not cover both ViewArea edges under rebase hysteresis"
                    + " playerY=" + mc.player.getY()
                    + " expectedCameraSection=" + expectedCameraSection
                    + " initialViewBase=" + initialViewBaseSection
                    + " alignment=" + alignment);
            return;
        }

        System.out.println("ENDLESS_RENDER_WINDOW_EDGE_PASS phase=hysteresis camera=" + alignment.cameraSection()
            + " lowerEdge=" + alignment.viewBaseSection()
            + " upperEdge=" + alignment.viewMaxSectionInclusive()
            + " tree=[" + alignment.treeBaseSection() + "," + alignment.treeMaxSectionInclusive() + "]"
            + " baseHeld=true");

        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "groundReturn", " integrated server disappeared before dense render check");
            return;
        }
        if (!groundTeleportQueued) {
            groundTeleportQueued = true;
            serverTask(server, "groundReturn", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                verifyDenseCanary(player.level(), "server before ground return");
                player.teleportTo(0.5D, GROUND_RETURN_Y, 0.5D);
                player.setNoGravity(true);
                groundTeleportServerDone = true;
            });
        }
        setStage(Stage.WAIT_GROUND_RETURN);
    }

    private static void waitForGroundReturn(Minecraft mc) {
        if (mc.level == null || mc.player == null || !groundTeleportServerDone) {
            requireStageWithin(mc, 1_200, "client/server did not complete ground-return setup");
            return;
        }
        boolean atGround = Math.abs(mc.player.getY() - GROUND_RETURN_Y) < 8.0D;
        boolean densePreserved = denseCanaryMatches(mc.level);
        boolean viewArea = LiveRenderProbe.sawViewAreaExact(DENSE_RENDER_POS);
        boolean renderGraph = LiveRenderProbe.sawRenderGraphExact(DENSE_RENDER_POS);
        if (!atGround || !densePreserved || !viewArea || !renderGraph) {
            requireStageWithin(mc, 1_200,
                "normal chunk did not survive/re-render after million-height rejoin"
                    + " playerY=" + mc.player.getY()
                    + " densePreserved=" + densePreserved
                    + " viewArea=" + viewArea
                    + " renderGraph=" + renderGraph
                    + " dense=" + denseCanaryDiagnostic(mc));
            return;
        }
        verifyDenseCanary(mc.level, "client ground return");
        done = true;
        stage = Stage.DONE;
        System.out.println("ENDLESS_SAME_JVM_DENSE_CHUNK_PASS targetChunk=0,0 densePreserved=true viewArea=true renderGraph=true");
        System.out.println(PASS_MARKER + " target=" + TARGET
            + " serverPersisted=true clientResynced=true blockEntitiesCompiled=true denseChunkPreserved=true sameJvm=true");
        System.out.flush();
        mc.stop();
    }

    private static boolean renderWindowAlignmentMatchesCamera(
        Minecraft mc, LiveRenderProbe.RenderWindowAlignment alignment
    ) {
        if (alignment == null || mc.player == null) return false;
        return alignment.cameraSection() == cameraSection(mc)
            && alignment.viewSectionCount() == 32
            && alignment.coversViewWindow()
            && alignment.treeBaseSection() <= alignment.viewBaseSection()
            && alignment.treeMaxSectionInclusive() >= alignment.viewMaxSectionInclusive();
    }

    private static int cameraSection(Minecraft mc) {
        // Rendering follows the eye/camera, which can cross a section boundary
        // while the player's feet remain in the section below it.
        return Math.floorDiv(mc.gameRenderer.getMainCamera().blockPosition().getY(), 16);
    }

    private static boolean blockEntitiesPresent(net.minecraft.world.level.Level level) {
        for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
            BlockState state = level.getBlockState(fixture.pos());
            BlockEntity blockEntity = level.getBlockEntity(fixture.pos());
            if (state != fixture.state() || blockEntity == null || !blockEntity.getType().isValid(state)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasVisibleSolidMesh(Minecraft mc, BlockPos pos) {
        return ((VisibleSectionsAccessor) (Object) mc.levelRenderer)
            .endless$getVisibleSections().stream().anyMatch(section ->
                section.getSectionNode() == SectionPos.asLong(pos)
                    && section.getSectionMesh().getSectionDraw(ChunkSectionLayer.SOLID) != null
                    && section.getSectionMesh().getSectionDraw(ChunkSectionLayer.SOLID).indexCount() > 0);
    }

    private static boolean blockEntitiesCompiled() {
        for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
            if (!LiveRenderProbe.sawBlockEntityCompiled(fixture.pos())) return false;
        }
        return true;
    }

    private static boolean blockEntitiesScannerDiscovered() {
        for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
            if (!LiveRenderProbe.sawBlockEntityScannerDiscovered(fixture.pos())) return false;
        }
        return true;
    }

    private static boolean blockEntitiesInternallyValid(net.minecraft.world.level.Level level) {
        for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
            BlockState state = level.getBlockState(fixture.pos());
            BlockEntity blockEntity = level.getBlockEntity(fixture.pos());
            if (blockEntity == null
                || blockEntity.isRemoved()
                || !blockEntity.hasLevel()
                || blockEntity.getBlockState() != state
                || !blockEntity.getType().isValid(blockEntity.getBlockState())) {
                return false;
            }
        }
        return true;
    }

    private static boolean blockEntitiesVisible(Minecraft mc) {
        java.util.Set<BlockPos> visible = new java.util.HashSet<>();
        mc.levelRenderer.iterateVisibleBlockEntities(blockEntity -> visible.add(blockEntity.getBlockPos()));
        for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
            if (!visible.contains(fixture.pos())) return false;
        }
        return true;
    }

    private static String blockEntityDiagnostic(net.minecraft.world.level.Level level) {
        StringBuilder result = new StringBuilder();
        for (BlockEntityFixture fixture : BLOCK_ENTITY_FIXTURES) {
            if (!result.isEmpty()) result.append(';');
            BlockState state = level.getBlockState(fixture.pos());
            BlockEntity blockEntity = level.getBlockEntity(fixture.pos());
            result.append(fixture.pos()).append('=').append(state)
                .append("/be=").append(blockEntity == null ? "null" : blockEntity.getType())
                .append("/queued=").append(LiveRenderProbe.sawBlockEntityRefreshQueued(fixture.pos()))
                .append("/dirtied=").append(LiveRenderProbe.sawBlockEntityRefreshDirtied(fixture.pos()))
                .append("/viewArea=").append(LiveRenderProbe.sawViewAreaExact(fixture.pos()))
                .append("/renderGraph=").append(LiveRenderProbe.sawRenderGraphExact(fixture.pos()))
                .append("/renderState=").append(LiveRenderProbe.sawRenderChunk(fixture.pos()))
                .append("/renderBE=").append(LiveRenderProbe.sawRenderChunkBlockEntity(fixture.pos()))
                .append("/sectionCompiled=").append(LiveRenderProbe.sawSectionCompiled(fixture.pos()))
                .append("/compiled=").append(LiveRenderProbe.sawBlockEntityCompiled(fixture.pos()));
        }
        return result.toString();
    }

    private static void requireValidBlockEntity(net.minecraft.world.level.Level level, BlockEntityFixture fixture, String phase) {
        BlockState state = level.getBlockState(fixture.pos());
        BlockEntity blockEntity = level.getBlockEntity(fixture.pos());
        require(state == fixture.state(), phase + " block state mismatch at " + fixture.pos()
            + " expected=" + fixture.state() + " actual=" + state);
        require(blockEntity != null, phase + " block entity missing at " + fixture.pos());
        require(blockEntity.getType().isValid(state), phase + " invalid block entity " + blockEntity.getType()
            + " for state " + state + " at " + fixture.pos());
    }

    private record BlockEntityFixture(BlockPos pos, BlockState state) {}

    private static BlockState[] captureDenseCanary(net.minecraft.world.level.BlockGetter level) {
        BlockState[] snapshot = new BlockState[16 * 16];
        int nonAir = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                BlockState state = level.getBlockState(new BlockPos(x, DENSE_CANARY_Y, z));
                snapshot[(z << 4) | x] = state;
                if (!state.isAir()) nonAir++;
            }
        }
        require(nonAir > 0, "dense worldgen canary plane unexpectedly contains only air");
        return snapshot;
    }

    private static boolean denseCanaryMatches(net.minecraft.world.level.BlockGetter level) {
        if (denseCanary == null) return false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (level.getBlockState(new BlockPos(x, DENSE_CANARY_Y, z)) != denseCanary[(z << 4) | x]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String denseCanaryDiagnostic(Minecraft mc) {
        try {
            var chunk = mc.level.getChunkSource().getChunkNow(0, 0);
            if (chunk == null) return "chunk=null";
            int index = chunk.getSectionIndex(DENSE_CANARY_Y);
            BlockState direct = index >= 0 && index < chunk.getSections().length
                ? chunk.getSections()[index].getBlockState(0, DENSE_CANARY_Y & 15, 0)
                : null;
            return "chunk=" + chunk.getClass().getSimpleName() + " sections=" + chunk.getSections().length
                + " index=" + index + " direct=" + direct;
        } catch (Throwable t) {
            return "diagError=" + t;
        }
    }
    private static void verifyDenseCanary(net.minecraft.world.level.BlockGetter level, String phase) {
        require(denseCanary != null, phase + " missing dense worldgen snapshot");
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                BlockPos pos = new BlockPos(x, DENSE_CANARY_Y, z);
                BlockState expected = denseCanary[(z << 4) | x];
                BlockState actual = level.getBlockState(pos);
                require(actual == expected, phase + " dense worldgen changed at " + pos
                    + " expected=" + expected + " actual=" + actual);
            }
        }
    }
    private static boolean clientModelsReady(Minecraft mc) {
        try {
            // ModelManager.requiresRender dereferences modelGroups, which vanilla
            // assigns only when the initial model reload is applied.
            mc.getModelManager().requiresRender(
                Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState());
            return true;
        } catch (NullPointerException notReady) {
            return false;
        }
    }
    private static void requireExpectedRange(Minecraft mc, String phase) {
        int expectedMin = intProperty("endless.sameJvmRejoinTest.expectedMin", -1_048_576);
        int expectedMax = intProperty("endless.sameJvmRejoinTest.expectedMax", 1_048_576);
        require(EndlessLogicalHeights.isActive(), phase + " logical heights are inactive");
        require(EndlessHeights.getMinBuildHeight() == expectedMin,
            phase + " min range mismatch: " + EndlessHeights.getMinBuildHeight());
        require(EndlessHeights.getMaxBuildHeight() == expectedMax,
            phase + " max range mismatch: " + EndlessHeights.getMaxBuildHeight());
        require(mc.level != null && !mc.level.isOutsideBuildHeight(TARGET_Y),
            phase + " client rejects target height " + TARGET_Y);
    }

    private static void requireStageWithin(Minecraft mc, int limit, String message) {
        if (ticks - stageTick >= limit) {
            fail(mc, "timeout", " stage=" + stage + " detail=" + message);
        }
    }

    private static void serverTask(MinecraftServer server, String phase, CheckedRunnable task) {
        server.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                ASYNC_FAILURE.compareAndSet(null, phase + ": " + t);
                t.printStackTrace();
            }
        });
    }

    private static ServerPlayer requirePlayer(MinecraftServer server, UUID uuid) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) throw new IllegalStateException("integrated-server player is missing: " + uuid);
        return player;
    }

    private static void setStage(Stage next) {
        if (stage == next) return;
        Stage previous = stage;
        stage = next;
        stageTick = ticks;
        System.out.println("ENDLESS_SAME_JVM_STAGE from=" + previous + " to=" + next + " tick=" + ticks);
        System.out.flush();
    }

    private static int intProperty(String key, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void fail(Minecraft mc, String phase, String details) {
        if (done) return;
        done = true;
        stage = Stage.DONE;
        System.out.println(FAIL_MARKER + " phase=" + phase + details);
        System.out.flush();
        mc.stop();
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private enum Stage {
        CREATE_WORLD,
        WAIT_BOOTSTRAP_JOIN,
        WAIT_BOOTSTRAP_CLOSED,
        WAIT_FIRST_JOIN,
        WAIT_FIXTURE,
        WAIT_PLACEMENT,
        WAIT_SAVE,
        WAIT_CLOSED,
        WAIT_SECOND_JOIN,
        WAIT_CLIENT_RESYNC,
        WAIT_HYSTERESIS_PROBE,
        WAIT_GROUND_RETURN,
        DONE
    }
}
