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
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    private static final AtomicReference<String> ASYNC_FAILURE = new AtomicReference<>();

    private static boolean armed;
    private static boolean done;
    private static Stage stage = Stage.CREATE_WORLD;
    private static int ticks;
    private static int stageTick;
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
    private static UUID playerUuid;

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
                case WAIT_FIRST_JOIN -> waitForFirstJoin(mc);
                case WAIT_FIXTURE -> waitForFixture(mc);
                case WAIT_PLACEMENT -> waitForPlacement(mc);
                case WAIT_SAVE -> waitForSave(mc);
                case WAIT_CLOSED -> waitForClosedAndReopen(mc);
                case WAIT_SECOND_JOIN -> waitForSecondJoin(mc);
                case WAIT_CLIENT_RESYNC -> waitForClientResync(mc);
                case DONE -> { }
            }
        } catch (Throwable t) {
            fail(mc, "clientException", " error=" + t);
            t.printStackTrace();
        }
    }

    private static void createWorld(Minecraft mc) {
        if (mc.level != null || mc.hasSingleplayerServer()) return;
        LevelSettings settings = new LevelSettings(
            "Endless Same-JVM Rejoin",
            GameType.CREATIVE,
            false,
            Difficulty.NORMAL,
            true,
            new GameRules(),
            WorldDataConfiguration.DEFAULT
        );
        setStage(Stage.WAIT_FIRST_JOIN);
        mc.createWorldOpenFlows().createFreshLevel(
            WORLD_ID,
            settings,
            new WorldOptions(0x5EEDL, true, false),
            WorldPresets::createNormalWorldDimensions
        );
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
        attemptPlacement(mc);
        setStage(Stage.WAIT_PLACEMENT);
    }

    private static void waitForPlacement(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            requireStageWithin(mc, 600, "client disconnected before sparse placement acknowledgement");
            return;
        }
        if (!mc.level.getBlockState(TARGET).is(Blocks.STONE)) {
            if (ticks - lastPlacementAttemptTick >= 20) {
                attemptPlacement(mc);
            }
            requireStageWithin(mc, 600,
                "real client sparse placement did not settle state=" + mc.level.getBlockState(TARGET)
                    + " attempts=" + placementAttempts
                    + " predictions=" + LivePredictionProbe.count(TARGET));
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(mc, "placement", " integrated server disappeared");
            return;
        }
        if (!placementCheckQueued) {
            placementCheckQueued = true;
            serverTask(server, "verifyPlacement", () -> {
                ServerPlayer player = requirePlayer(server, playerUuid);
                require(player.serverLevel().getBlockState(TARGET).is(Blocks.STONE),
                    "server did not receive real client sparse placement");
                serverSawPlacement = true;
            });
        }
        if (!serverSawPlacement) {
            requireStageWithin(mc, 600, "server never acknowledged sparse placement");
            return;
        }
        System.out.println(PLACEMENT_MARKER + " target=" + TARGET + " attempts=" + placementAttempts
            + " predictionAcks=" + LivePredictionProbe.count(TARGET) + " server=true");
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
            mc.clearLevel();
            return;
        }
        if (mc.level != null || mc.hasSingleplayerServer()) {
            requireStageWithin(mc, 1_000, "first integrated server did not fully clear from the client");
            return;
        }
        if (!reopenIssued) {
            reopenIssued = true;
            setStage(Stage.WAIT_SECOND_JOIN);
            mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD_ID);
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
        if (!playerAtTarget || !clientHasBlock) {
            requireStageWithin(mc, 1_200,
                "saved sparse page was not resynchronized after same-JVM reopen"
                    + " playerY=" + mc.player.getY()
                    + " clientState=" + mc.level.getBlockState(TARGET)
                    + " logical=" + EndlessLogicalHeights.isActive());
            return;
        }
        done = true;
        stage = Stage.DONE;
        System.out.println(PASS_MARKER + " target=" + TARGET
            + " serverPersisted=true clientResynced=true sameJvm=true");
        mc.stop();
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
        WAIT_FIRST_JOIN,
        WAIT_FIXTURE,
        WAIT_PLACEMENT,
        WAIT_SAVE,
        WAIT_CLOSED,
        WAIT_SECOND_JOIN,
        WAIT_CLIENT_RESYNC,
        DONE
    }
}
