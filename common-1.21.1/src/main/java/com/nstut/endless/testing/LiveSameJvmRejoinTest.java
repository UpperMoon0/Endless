package com.nstut.endless.testing;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
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
    private static final int TARGET_Y = 1_000_000;
    private static final BlockPos SUPPORT = new BlockPos(0, TARGET_Y - 1, 0);
    private static final BlockPos TARGET = SUPPORT.above();
    private static final int MAX_TICKS = 4_000;
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
    private static boolean bootstrapClearIssued;
    private static boolean bootstrapReopenIssued;
    private static boolean fixtureTaskQueued;
    private static volatile boolean fixtureReady;
    private static int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private static int placementAttempts;
    private static boolean placementCheckQueued;
    private static volatile boolean serverSawPlacement;
    private static boolean saveTaskQueued;
    private static volatile boolean saveComplete;
    private static boolean serverStopRequested;
    private static boolean clearIssued;
    private static boolean reopenIssued;
    private static boolean rejoinCheckQueued;
    private static volatile boolean reopenedServerSawBlock;
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
            false,
            Difficulty.NORMAL,
            true,
            new GameRules(),
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
                ServerLevel level = player.serverLevel();
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
            requireStageWithin(mc, 1_200, "bootstrap terrain save did not complete");
            return;
        }
        if (!bootstrapStopRequested) {
            bootstrapStopRequested = true;
            server.halt(false);
            setStage(Stage.WAIT_BOOTSTRAP_CLOSED);
        }
    }

    private static void waitForBootstrapClosedAndMigrate(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (!bootstrapClearIssued) {
            if (server != null && !server.isShutdown()) {
                requireStageWithin(mc, 1_000, "bootstrap integrated server did not stop");
                return;
            }
            bootstrapClearIssued = true;
            mc.disconnect();
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
                ServerLevel level = player.serverLevel();
                require(!level.isOutsideBuildHeight(TARGET_Y), "target is outside logical build range");
                require(level.getSectionsCount() == 254,
                    "migrated legacy dense core did not load as 254 sections: " + level.getSectionsCount());
                require(level.getChunk(0, 0).getSections().length == 254,
                    "migrated target chunk did not allocate 254 dense sections");
                verifyDenseCanary(level, "migrated pre-placement");
                require(EndlessHeights.isOutsideDenseBuildHeight(TARGET_Y),
                    "target must exercise sparse storage, not the dense core");
                require(level.setBlock(SUPPORT, Blocks.DEEPSLATE.defaultBlockState(), 3),
                    "could not create sparse placement support");
                require(level.removeBlock(TARGET, false) || level.getBlockState(TARGET).isAir(),
                    "could not clear sparse placement target");
                player.setGameMode(GameType.CREATIVE);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE));
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
        boolean supportVisible = mc.level.getBlockState(SUPPORT).is(Blocks.DEEPSLATE);
        boolean targetClear = mc.level.getBlockState(TARGET).isAir();
        boolean holdingStone = mc.player.getMainHandItem().is(Blocks.STONE.asItem());
        if (!playerReady || !supportVisible || !targetClear || !holdingStone) {
            requireStageWithin(mc, 1_000,
                "client never received first sparse fixture playerY=" + mc.player.getY()
                    + " support=" + mc.level.getBlockState(SUPPORT)
                    + " target=" + mc.level.getBlockState(TARGET)
                    + " held=" + mc.player.getMainHandItem());
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "placement", " integrated server disappeared before sparse edit");
            return;
        }
        if (!placementCheckQueued) {
            placementCheckQueued = true;
            serverTask(server, "applySparseEdit", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                require(player.serverLevel().setBlock(TARGET, Blocks.STONE.defaultBlockState(), 3),
                    "server could not apply sparse million-height edit");
                require(player.serverLevel().getBlockState(TARGET).is(Blocks.STONE),
                    "server sparse edit did not settle");
                verifyDenseCanary(player.serverLevel(), "after sparse edit");
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
                require(player.serverLevel().getBlockState(TARGET).is(Blocks.STONE),
                    "sparse block vanished before save");
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
            requireStageWithin(mc, 600, "integrated server save did not complete");
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "save", " integrated server disappeared before shutdown");
            return;
        }
        if (!serverStopRequested) {
            serverStopRequested = true;
            System.out.println(SAVE_MARKER + " target=" + TARGET + " flushed=true");
            // Request the same integrated-server shutdown that Save & Quit drives,
            // but do it outside Minecraft.clearLevel() so the automated tick hook
            // cannot deadlock while clearLevel waits for the server thread.
            server.halt(false);
            setStage(Stage.WAIT_CLOSED);
        }
    }

    private static void waitForClosedAndReopen(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (!clearIssued) {
            if (server != null && !server.isShutdown()) {
                requireStageWithin(mc, 1_000, "first integrated server did not stop after save");
                return;
            }
            clearIssued = true;
            mc.disconnect();
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
                require(player.serverLevel().getBlockState(TARGET).is(Blocks.STONE),
                    "saved sparse block is missing on reopened integrated server");
                verifyDenseCanary(player.serverLevel(), "server reopen");
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
        boolean clientDensePreserved = denseCanaryMatches(mc.level);
        if (!playerAtTarget || !clientHasBlock || !clientDensePreserved) {
            requireStageWithin(mc, 1_200,
                "saved sparse page was not resynchronized after same-JVM reopen"
                    + " playerY=" + mc.player.getY()
                    + " clientState=" + mc.level.getBlockState(TARGET)
                    + " logical=" + EndlessLogicalHeights.isActive() + " densePreserved=" + clientDensePreserved + " dense=" + denseCanaryDiagnostic(mc));
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "groundReturn", " integrated server disappeared before dense render check");
            return;
        }
        if (!groundTeleportQueued) {
            groundTeleportQueued = true;
            serverTask(server, "groundReturn", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                verifyDenseCanary(player.serverLevel(), "server before ground return");
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
            + " serverPersisted=true clientResynced=true denseChunkPreserved=true sameJvm=true");
        mc.stop();
    }

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
        stage = next;
        stageTick = ticks;
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
        WAIT_GROUND_RETURN,
        DONE
    }
}
