package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime holder for the effective build range. The file config expresses user
 * intent; the effective range is what the world actually uses:
 *
 * <ul>
 *   <li>Dedicated/integrated servers: {@link #loadPersistedRange} runs before
 *       any ServerLevel exists (Fabric SERVER_STARTING / Forge
 *       ServerAboutToStart). v0.4 worlds read their persisted range directly
 *       from disk. Pre-v0.4 played worlds are classified before any chunk can
 *       deserialize: the raw global config is only a candidate, all saved
 *       region evidence is checked against it, and ambiguous/unrepresentable
 *       histories fail closed instead of silently dropping sections.</li>
 *   <li>Remote clients: begin every remote connection with the vanilla range
 *       and adopt the server's range during the login phase only if the
 *       server provides one (Fabric via {@code ServerLoginNetworking}, Forge
 *       via a Forge login packet). The baseline is established when
 *       {@code ClientHandshakePacketListenerImpl} is constructed, before
 *       authentication-specific packets can be skipped, and re-established
 *       defensively at the login packet via
 *       {@link #applyVanillaBaselineIfUnapplied}.</li>
 * </ul>
 */
public final class EndlessHeights {

    public static final int VANILLA_MIN_BUILD_HEIGHT = -64;
    public static final int VANILLA_MAX_BUILD_HEIGHT = 320;

    private static volatile boolean applied;
    private static volatile int effectiveMin;
    private static volatile int effectiveMax;

    private EndlessHeights() {
    }

    public static int getMinBuildHeight() {
        if (applied) {
            return effectiveMin;
        }
        return EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
    }

    public static int getMaxBuildHeight() {
        if (applied) {
            return effectiveMax;
        }
        return EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight();
    }

    public static int getHeight() {
        int raw = getMaxBuildHeight() - getMinBuildHeight();
        return Math.min(raw, EndlessConfig.MAX_SECTIONS * 16);
    }

    public static boolean isOutsideBuildHeight(int y) {
        int min = getMinBuildHeight();
        return y < min || y >= min + getHeight();
    }

    /** Merge the world-persisted range with config without shrinking it. */
    public static int[] mergeRange(int savedMin, int savedMax, int configMin, int configMax) {
        return new int[]{Math.min(savedMin, configMin), Math.max(savedMax, configMax)};
    }

    private static EndlessConfig.BuildHeightConfig clampedFileConfig() {
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        EndlessConfig.BuildHeightConfig file = EndlessConfig.getInstance().getBuildHeight();
        tmp.setMinBuildHeight(file.getMinBuildHeight());
        tmp.setMaxBuildHeight(file.getMaxBuildHeight());
        tmp.clamp();
        return tmp;
    }

    /**
     * Apply the world's persisted or safely migrated range before any
     * ServerLevel is created. Vanilla ChunkSerializer allocates its section
     * array from the current LevelHeightAccessor and skips saved signed-byte Y
     * values that map outside it; a guessed narrower range can therefore become
     * permanent data loss on the next save.
     */
    public static void loadPersistedRange(MinecraftServer server) {
        int[] saved = readSavedRange(server);
        EndlessConfig.BuildHeightConfig cfg = clampedFileConfig();
        int[] merged;

        if (saved == null) {
            merged = classifyUnpersistedWorld(server, cfg);
        } else {
            merged = mergeRange(saved[0], saved[1], cfg.getMinBuildHeight(), cfg.getMaxBuildHeight());
            if (cfg.getMinBuildHeight() > saved[0] || cfg.getMaxBuildHeight() < saved[1]) {
                System.err.println("Endless: config range [" + cfg.getMinBuildHeight() + ", " + cfg.getMaxBuildHeight()
                    + ") is narrower than this world's persisted range [" + saved[0] + ", " + saved[1]
                    + "); keeping the wider world range. Saved sections would be unreachable otherwise.");
            }
        }

        applyEffective(merged[0], merged[1]);
        EndlessConfig.getInstance().saveNormalizedIfNeeded();
    }

    private static int[] classifyUnpersistedWorld(
        MinecraftServer server,
        EndlessConfig.BuildHeightConfig clampedConfig
    ) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        final boolean playedWorld;
        try {
            playedWorld = LegacyRegionScanner.hasPlayedRegionData(worldRoot);
        } catch (IOException e) {
            throw migrationFailure("could not inspect the world for region files", e);
        }

        if (!playedWorld) {
            return new int[]{clampedConfig.getMinBuildHeight(), clampedConfig.getMaxBuildHeight()};
        }

        EndlessConfig.BuildHeightConfig raw = EndlessConfig.getInstance().getRawLoadedBuildHeight();
        if (raw == null) {
            throw migrationFailure(
                "played pre-v0.4 world has no persisted range and no trustworthy raw buildHeight config. "
                    + "Endless will not guess a narrower section layout because vanilla would silently skip "
                    + "out-of-range saved sections on load. Restore the legacy config or convert the world explicitly.",
                null
            );
        }

        LegacyWorldMigration.Resolution resolution = LegacyWorldMigration.classify(
            raw.getMinBuildHeight(), raw.getMaxBuildHeight());
        if (resolution.status() == LegacyWorldMigration.Status.REFUSE) {
            throw migrationFailure(resolution.reason(), null);
        }

        // Pre-v0.4 config was global, not per-world. Even a syntactically safe
        // raw config is therefore only a candidate: World A may have been last
        // played with a wider range before the user changed endless.json for
        // World B. Scan every saved chunk before constructing any level.
        final LegacyRegionScanner.WorldEvidence evidence;
        try {
            evidence = LegacyRegionScanner.scanWorldAgainstCandidate(
                worldRoot,
                resolution.migratedMin(),
                resolution.migratedMax(),
                resolution.legacyHeight()
            );
        } catch (IOException | RuntimeException e) {
            throw migrationFailure("could not safely inspect legacy world data against the candidate range", e);
        }

        resolution = LegacyWorldMigration.resolveWorldInspection(resolution, evidence);
        if (resolution.status() == LegacyWorldMigration.Status.REFUSE) {
            throw migrationFailure(resolution.reason(), null);
        }

        System.err.println("Endless: safely classified pre-v0.4 world. Raw legacy config ["
            + raw.getMinBuildHeight() + ", " + raw.getMaxBuildHeight() + ") -> initial persisted range ["
            + resolution.migratedMin() + ", " + resolution.migratedMax() + ").");
        return new int[]{resolution.migratedMin(), resolution.migratedMax()};
    }

    private static IllegalStateException migrationFailure(String reason, Throwable cause) {
        String message = "Endless: REFUSING TO LOAD LEGACY WORLD - " + reason
            + " No chunks have been loaded by Endless at this migration gate. Back up the world before changing "
            + "anything; automatic startup is blocked to prevent irreversible section loss.";
        System.err.println(message);
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    /** Mirror the effective range into normal SavedData after levels exist. */
    public static void syncWorldData(MinecraftServer server) {
        EndlessWorldData data = server.overworld().getDataStorage()
            .computeIfAbsent(EndlessWorldData::load, EndlessWorldData::new, EndlessWorldData.DATA_NAME);
        if (data.getMinBuildHeight() != getMinBuildHeight()
            || data.getMaxBuildHeight() != getMaxBuildHeight()) {
            data.set(getMinBuildHeight(), getMaxBuildHeight());
        }
        data.setDirty();
    }

    /**
     * Read v0.4+ world metadata. Existing but unreadable/out-of-envelope data
     * is a hard failure rather than an invitation to replace the layout from
     * the current file config.
     */
    private static int[] readSavedRange(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT)
            .resolve("data")
            .resolve(EndlessWorldData.DATA_NAME + ".dat");
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try {
            CompoundTag root = NbtIo.readCompressed(file.toFile());
            CompoundTag data = root.getCompound("data");
            if (!data.contains("MinBuildHeight") || !data.contains("MaxBuildHeight")) {
                throw new IOException("persisted range file is missing MinBuildHeight/MaxBuildHeight");
            }

            int savedMin = data.getInt("MinBuildHeight");
            int savedMax = data.getInt("MaxBuildHeight");
            EndlessConfig.BuildHeightConfig normalized = new EndlessConfig.BuildHeightConfig();
            normalized.setMinBuildHeight(savedMin);
            normalized.setMaxBuildHeight(savedMax);
            normalized.clamp();
            if (normalized.getMinBuildHeight() != savedMin || normalized.getMaxBuildHeight() != savedMax) {
                throw new IOException("persisted range [" + savedMin + ", " + savedMax
                    + ") is not an aligned v0.4-safe range");
            }
            return new int[]{savedMin, savedMax};
        } catch (IOException | RuntimeException e) {
            throw migrationFailure("could not trust persisted build range at " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Force the vanilla baseline for every remote login connection. A local
     * memory connection belongs to the integrated server and shares the
     * already-authoritative effective range in this JVM, so it is left alone.
     */
    public static void applyVanillaBaselineForNewConnection(boolean memoryConnection) {
        if (memoryConnection) {
            return;
        }
        applyEffective(VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT);
    }

    public static void applyVanillaBaselineIfUnapplied() {
        if (!applied) {
            applyEffective(VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT);
        }
    }

    /** Apply an authoritative, aligned, guarded range. */
    public static void applyEffective(int min, int max) {
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        tmp.setMinBuildHeight(min);
        tmp.setMaxBuildHeight(max);
        tmp.clamp();
        effectiveMin = tmp.getMinBuildHeight();
        effectiveMax = tmp.getMaxBuildHeight();
        applied = true;
    }

    public static void resetToLocalConfig() {
        applied = false;
    }
}
