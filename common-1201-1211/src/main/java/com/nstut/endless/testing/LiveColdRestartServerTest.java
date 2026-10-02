package com.nstut.endless.testing;

import com.nstut.endless.compat.create.CreateKineticIdData;
import com.nstut.endless.compat.create.CreateKineticStorageProbe;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Two-process persistence test. Phase B runs in a fresh dedicated-server JVM on phase A's world. */
public final class LiveColdRestartServerTest {
    public static final String PHASE_PROPERTY = "endless.liveJoinColdRestartPhase";
    public static final String PHASE_A_PASS = "ENDLESS_COLD_RESTART_PHASE_A_PASS";
    public static final String PHASE_B_PASS = "ENDLESS_COLD_RESTART_PHASE_B_PASS";
    public static final String FAIL_MARKER = "ENDLESS_COLD_RESTART_FAIL";
    public static final String CREATE_PROPERTY = "endless.liveJoinCreateTest";
    public static final String CREATE_KINETIC_PASS = "ENDLESS_CREATE_KINETIC_COLD_RESTART_PASS";

    private static boolean done;
    private static boolean prepared;
    private static boolean createRecovered;
    private static boolean factoryRecovered;
    private static boolean logisticsRecovered;
    private static boolean gantryRecovered;
    private static boolean clockworkRecovered;
    private static int ticks;
    private static int fixtureTickEligibleAt = -1;

    private LiveColdRestartServerTest() {}

    public static void tick(MinecraftServer server) {
        String phase = System.getProperty(PHASE_PROPERTY, "").trim();
        if (done || phase.isEmpty()) return;
        if (server.getPlayerList().getPlayers().isEmpty()) {
            if (ticks > 0) {
                done = true;
                System.out.println(FAIL_MARKER + " phase=" + phase + " error=clientDisconnectedBeforeCompletion");
                throw new IllegalStateException("cold-restart client disconnected before server completion");
            }
            return;
        }
        ticks++;
        if (ticks < 10) return;
        ServerLevel level = server.overworld();
        try {
            if (phase.equalsIgnoreCase("A")) {
                if (!prepared) {
                    forceFixtureChunk(level);
                    prepare(level);
                    prepared = true;
                    return;
                }
                if (!fixtureReady(level)) return;
                verify(level);
                persistCreateExpectedIdsIfRequested(level);
                if (Boolean.getBoolean(CREATE_PROPERTY)) {
                    LiveCreateMovingRestartTest.prepare(level);
                    LiveCreateFactoryRestartTest.prepare(level);
                    LiveCreateLogisticsRestartTest.prepare(level);
                    LiveCreateGantryRestartTest.prepare(level);
                    LiveCreateClockworkRestartTest.prepare(level);
                }
                ExtendedPoiStorage.flush(level, new ChunkPos(poiPos()));
                EndlessVerticalEngine.world(level).flushDirty();
                require(server.saveEverything(true, true, true), "dedicated server saveEverything reported failure");
                done = true;
                System.out.println(PHASE_A_PASS + " worldSaved=true");
            } else if (phase.equalsIgnoreCase("B")) {
                if (!prepared) {
                    forceFixtureChunk(level);
                    prepared = true;
                    return;
                }
                if (!fixtureReady(level)) return;
                verify(level);
                if (Boolean.getBoolean(CREATE_PROPERTY)) {
                    // Persistence has passed independently; only now construct
                    // separate legacy-NBT migration and corrupt-storage fixtures.
                    if (!createRecovered) {
                    LiveCreatePositionCodecTest.run(level);
                    LiveCreateChorusTest.run(level);
                    LiveCreateMovingRestartTest.verify(level);
                    createRecovered = true;
                    }
                    if (!clockworkRecovered) clockworkRecovered = LiveCreateClockworkRestartTest.verify(level);
                    if (!factoryRecovered) {
                        if (!LiveCreateFactoryRestartTest.verify(level)) return;
                        factoryRecovered = true;
                    }
                    if (!logisticsRecovered) {
                        if (!LiveCreateLogisticsRestartTest.verify(level)) return;
                        logisticsRecovered = true;
                    }
                    if (!gantryRecovered) {
                        if (!LiveCreateGantryRestartTest.verify(level)) return;
                        gantryRecovered = true;
                    }
                    if (!clockworkRecovered) return;
                    LiveCreateMigrationTest.run(level);
                    CreateKineticStorageProbe.run(level.getServer().getWorldPath(LevelResource.ROOT)
                        .resolve("endless-live-allocator-probes"));
                    // Require exact persistence and native machine APIs across sparse boundaries.
                    LiveCreateContraptionSerializationTest.run(level);
                    LiveCreateExpandedMachinesTest.run(level);
                }
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

    private static void forceFixtureChunk(ServerLevel level) {
        level.setChunkForced(0, 0, true);
        if (Boolean.getBoolean(CREATE_PROPERTY)) { level.setChunkForced(2, 2, true); level.setChunkForced(6, 4, true); level.setChunkForced(8, 4, true); }
        require(level.getForcedChunks().contains(fixtureChunkKey()),
            "could not force-load cold-restart fixture chunk 0,0");
    }

    private static boolean fixtureReady(ServerLevel level) {
        long chunkKey = fixtureChunkKey();
        boolean tickEligible = level.areEntitiesLoaded(chunkKey)
            && level.getChunkSource().isPositionTicking(chunkKey)
            && (!Boolean.getBoolean(CREATE_PROPERTY) || (level.areEntitiesLoaded(ChunkPos.asLong(2, 2))
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(2, 2))
                && level.areEntitiesLoaded(ChunkPos.asLong(6, 4)) && level.getChunkSource().isPositionTicking(ChunkPos.asLong(6, 4))
                && level.areEntitiesLoaded(ChunkPos.asLong(8, 4)) && level.getChunkSource().isPositionTicking(ChunkPos.asLong(8, 4))));
        if (!tickEligible) {
            require(ticks < 210,
                "cold-restart fixture chunk never entered vanilla ticking state" + fixtureChunkStatus(level));
            return false;
        }
        if (fixtureTickEligibleAt < 0) fixtureTickEligibleAt = ticks;

        if (!Boolean.parseBoolean(System.getProperty(CREATE_PROPERTY, "false"))) return true;
        if (createNetworksInitialized(level)) return true;

        require(ticks < fixtureTickEligibleAt + 200,
            "Create generators did not initialize after fixture chunk became tick-eligible" + fixtureChunkStatus(level));
        return false;
    }

    private static boolean createNetworksInitialized(ServerLevel level) {
        try {
            Object motorA = level.getBlockEntity(createMotorA());
            Object motorB = level.getBlockEntity(createMotorB());
            if (motorA == null || motorB == null) return false;
            Class<?> kineticClass = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
            Long networkA = (Long) kineticClass.getField("network").get(motorA);
            Long networkB = (Long) kineticClass.getField("network").get(motorB);
            return networkA != null && networkB != null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not inspect Create kinetic readiness", e);
        }
    }

    private static long fixtureChunkKey() {
        return ChunkPos.asLong(0, 0);
    }

    private static String fixtureChunkStatus(ServerLevel level) {
        long chunkKey = fixtureChunkKey();
        return " gameTime=" + level.getGameTime()
            + " entitiesLoaded=" + level.areEntitiesLoaded(chunkKey)
            + " positionTicking=" + level.getChunkSource().isPositionTicking(chunkKey)
            + " shouldTickBlocks=" + level.shouldTickBlocksAt(chunkKey)
            + " forced=" + level.getForcedChunks().contains(chunkKey);
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
        prepareCreateIfRequested(level);
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
        verifyCreateIfRequested(level);
    }

    private static void prepareCreateIfRequested(ServerLevel level) {
        if (!Boolean.parseBoolean(System.getProperty(CREATE_PROPERTY, "false"))) return;

        // Allocate one unused position first. If the SavedData mapping were lost
        // between JVMs, phase B would restart at sequence 0 and no longer match
        // the network IDs persisted in the two Create block entities.
        CreateKineticIdData.idFor(level, createSentinelPos());

        Block motor = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse("create:creative_motor"));
        require(motor != Blocks.AIR, "Create creative motor missing from cold-restart runtime");
        require(createMotorA().asLong() == createMotorB().asLong(),
            "cold-restart Create motor fixtures must collide under vanilla BlockPos#asLong");
        require(level.setBlock(createMotorA(), motor.defaultBlockState(), 3),
            "cold-restart first Create motor write failed");
        require(level.setBlock(createMotorB(), motor.defaultBlockState(), 3),
            "cold-restart second Create motor write failed");
    }

    private static Path expectedIdsFile(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("endless-live-create-expected-ids.txt");
    }

    private static void persistCreateExpectedIdsIfRequested(ServerLevel level) throws IOException {
        if (!Boolean.getBoolean(CREATE_PROPERTY)) return;
        // Independent oracle, not allocator state and not regenerated in phase B.
        // Leave the actual synthetic IDs in the motors' saved NBT unchanged.
        Files.write(expectedIdsFile(level), List.of(
            Long.toString(CreateKineticIdData.idFor(level, createMotorA())),
            Long.toString(CreateKineticIdData.idFor(level, createMotorB())),
            Long.toString(CreateKineticIdData.idFor(level, createSentinelPos()))));
        System.out.println("ENDLESS_CREATE_EXPECTED_IDENTITIES_SAVED independent=true legacyOverwrite=false");
    }

    private static void verifyExpectedIds(ServerLevel level, long networkA, long networkB,
                                         long allocatedA, long allocatedB) {
        if (!"B".equalsIgnoreCase(System.getProperty(PHASE_PROPERTY, ""))) return;
        try {
            List<String> expected = Files.readAllLines(expectedIdsFile(level));
            require(expected.size() == 3, "ENDLESS_CREATE_PERSISTENCE_MISMATCH invalid independent identity checkpoint");
            long expectedA = Long.parseLong(expected.get(0));
            long expectedB = Long.parseLong(expected.get(1));
            long expectedSentinel = Long.parseLong(expected.get(2));
            require(networkA == expectedA && networkB == expectedB
                    && allocatedA == expectedA && allocatedB == expectedB
                    && CreateKineticIdData.idFor(level, createSentinelPos()) == expectedSentinel,
                "ENDLESS_CREATE_PERSISTENCE_MISMATCH phase-A identities changed"
                    + " expected=" + expectedA + "/" + expectedB
                    + " network=" + networkA + "/" + networkB
                    + " allocator=" + allocatedA + "/" + allocatedB);
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("ENDLESS_CREATE_PERSISTENCE_MISMATCH independent identity checkpoint unreadable", e);
        }
    }

    private static void verifyCreateIfRequested(ServerLevel level) {
        if (!Boolean.parseBoolean(System.getProperty(CREATE_PROPERTY, "false"))) return;
        try {
            Object motorA = level.getBlockEntity(createMotorA());
            Object motorB = level.getBlockEntity(createMotorB());
            require(motorA != null && motorB != null,
                "cold-restart Create motor block entities missing");

            Class<?> kineticClass = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
            Long networkA = (Long) kineticClass.getField("network").get(motorA);
            Long networkB = (Long) kineticClass.getField("network").get(motorB);
            require(networkA != null && networkB != null && !networkA.equals(networkB),
                "cold-restart Create generator networks are missing or aliased"
                    + " networkA=" + networkA + " networkB=" + networkB);

            long allocatedA = CreateKineticIdData.idFor(level, createMotorA());
            long allocatedB = CreateKineticIdData.idFor(level, createMotorB());
            verifyExpectedIds(level, networkA, networkB, allocatedA, allocatedB);
            require(networkA.longValue() == allocatedA && networkB.longValue() == allocatedB,
                "cold-restart Create SavedData identity does not match persisted block-entity networks"
                    + " networkA=" + networkA + " allocatedA=" + allocatedA
                    + " networkB=" + networkB + " allocatedB=" + allocatedB);

            Object objectA = kineticClass.getMethod("getOrCreateNetwork").invoke(motorA);
            Object objectB = kineticClass.getMethod("getOrCreateNetwork").invoke(motorB);
            require(objectA != objectB, "cold-restart Create generators resolved to the same KineticNetwork");

            System.out.println(CREATE_KINETIC_PASS
                + " phase=" + System.getProperty(PHASE_PROPERTY, "")
                + " networkA=" + networkA + " networkB=" + networkB);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not verify Create kinetic state during cold restart", e);
        }
    }

    private static int lowerY() { return EndlessHeights.getMinBuildHeight() + 16; }
    private static int upperY() { return EndlessHeights.getMaxBuildHeight() - 17; }
    private static BlockPos glowPos() { return new BlockPos(0, lowerY(), 10); }
    private static BlockPos waterPos() { return new BlockPos(1, upperY(), 10); }
    private static BlockPos chestPos() { return new BlockPos(2, lowerY(), 10); }
    private static BlockPos powerPos() { return new BlockPos(4, upperY(), 10); }
    private static BlockPos lampPos() { return new BlockPos(5, upperY(), 10); }
    private static BlockPos poiPos() { return new BlockPos(6, lowerY(), 10); }
    private static BlockPos createSentinelPos() { return new BlockPos(7, upperY() - 8192, 10); }
    private static BlockPos createMotorA() { return new BlockPos(8, upperY() - 4096, 10); }
    private static BlockPos createMotorB() { return createMotorA().above(4096); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
