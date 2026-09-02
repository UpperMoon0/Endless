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
 * Runtime holder for the logical build range and the separately bounded dense core.
 *
 * <p>v0.5 deliberately separates two concepts that v0.4 had to conflate:</p>
 * <ul>
 *   <li>The <b>logical range</b> is user intent from endless.json. It controls
 *       buildability, commands, sparse routing and teleport validity, and may be
 *       widened or narrowed between launches.</li>
 *   <li>The <b>dense core</b> is the vanilla Anvil/section-array layout. Fresh
 *       v0.5 worlds keep the vanilla [-64,320) core; migrated/existing worlds
 *       may retain a wider historical dense core, which never shrinks so old
 *       Anvil sections cannot be silently dropped.</li>
 * </ul>
 */
public final class EndlessHeights {

    public static final int VANILLA_MIN_BUILD_HEIGHT = -64;
    public static final int VANILLA_MAX_BUILD_HEIGHT = 320;

    private static volatile boolean applied;
    private static volatile int effectiveMin;
    private static volatile int effectiveMax;
    private static volatile int effectiveDenseMin;
    private static volatile int effectiveDenseMax;

    private EndlessHeights() {
    }

    /** Current logical/user build minimum. */
    public static int getMinBuildHeight() {
        if (applied) {
            return effectiveMin;
        }
        return EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
    }

    /** Current logical/user build maximum (exclusive). */
    public static int getMaxBuildHeight() {
        if (applied) {
            return effectiveMax;
        }
        return EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight();
    }

    /** Current vanilla-compatible dense core minimum. */
    public static int getDenseMinBuildHeight() {
        if (applied) {
            return effectiveDenseMin;
        }
        return VANILLA_MIN_BUILD_HEIGHT;
    }

    /** Current vanilla-compatible dense core maximum (exclusive). */
    public static int getDenseMaxBuildHeight() {
        if (applied) {
            return effectiveDenseMax;
        }
        return VANILLA_MAX_BUILD_HEIGHT;
    }

    /** Vanilla LevelHeightAccessor height. Never proportional to the sparse logical range. */
    public static int getHeight() {
        return getDenseMaxBuildHeight() - getDenseMinBuildHeight();
    }

    public static boolean isOutsideBuildHeight(int y) {
        return y < getMinBuildHeight() || y >= getMaxBuildHeight();
    }

    public static boolean isOutsideDenseBuildHeight(int y) {
        return y < getDenseMinBuildHeight() || y >= getDenseMaxBuildHeight();
    }

    /**
     * Dense-core candidate for a fresh v0.5 world.
     *
     * <p>The logical arguments are intentionally ignored. v0.5's sparse engine
     * exists specifically so widening the user build range does not widen
     * vanilla LevelChunkSection arrays. The vanilla core is also retained when
     * the logical range excludes part of it because worldgen/chunk bootstrap
     * still needs a normal internal dimension; logical build guards keep that
     * implementation detail inaccessible to players and commands.</p>
     */
    public static int[] denseRangeForLogical(int logicalMin, int logicalMax) {
        return new int[]{VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT};
    }

    /** Merge a saved dense core with a candidate dense core without shrinking it. */
    public static int[] mergeRange(int savedMin, int savedMax, int candidateMin, int candidateMax) {
        return new int[]{Math.min(savedMin, candidateMin), Math.max(savedMax, candidateMax)};
    }

    private static EndlessConfig.BuildHeightConfig normalizedFileConfig() {
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        EndlessConfig.BuildHeightConfig file = EndlessConfig.getInstance().getBuildHeight();
        tmp.setMinBuildHeight(file.getMinBuildHeight());
        tmp.setMaxBuildHeight(file.getMaxBuildHeight());
        tmp.clamp();
        return tmp;
    }

    /**
     * Resolve logical user intent plus the world-stable dense core before any
     * ServerLevel exists. Pre-v0.4 worlds are still classified before any chunk
     * can deserialize, because guessing a narrower dense array can permanently
     * discard signed-byte Anvil sections on the next save.
     */
    public static void loadPersistedRange(MinecraftServer server) {
        EndlessConfig.BuildHeightConfig cfg = normalizedFileConfig();
        int logicalMin = cfg.getMinBuildHeight();
        int logicalMax = cfg.getMaxBuildHeight();
        int[] candidateDense = denseRangeForLogical(logicalMin, logicalMax);
        int[] savedDense = readSavedRange(server);
        int[] dense;

        if (savedDense == null) {
            dense = classifyUnpersistedWorld(server, cfg, candidateDense);
        } else {
            dense = mergeRange(savedDense[0], savedDense[1], candidateDense[0], candidateDense[1]);
            if (savedDense[0] < candidateDense[0] || savedDense[1] > candidateDense[1]) {
                System.err.println("Endless: logical config range [" + logicalMin + ", " + logicalMax
                    + ") is active, while the internal dense core remains [" + dense[0] + ", " + dense[1]
                    + ") for Anvil safety. The wider dense core does not widen the configured build limit.");
            }
        }

        applyEffective(logicalMin, logicalMax, dense[0], dense[1]);
        EndlessConfig.getInstance().saveNormalizedIfNeeded();
    }

    private static int[] classifyUnpersistedWorld(
        MinecraftServer server,
        EndlessConfig.BuildHeightConfig normalizedConfig,
        int[] candidateDense
    ) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        final boolean playedWorld;
        try {
            playedWorld = LegacyRegionScanner.hasPlayedRegionData(worldRoot);
        } catch (IOException e) {
            throw migrationFailure("could not inspect the world for region files", e);
        }

        if (!playedWorld) {
            return candidateDense;
        }

        EndlessConfig.BuildHeightConfig raw = EndlessConfig.getInstance().getRawLoadedBuildHeight();
        if (raw == null) {
            throw migrationFailure(
                "played pre-v0.4 world has no persisted dense range and no trustworthy raw buildHeight config. "
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
        // played with a wider dense range before the user changed endless.json
        // for World B. Scan every saved chunk before constructing any level.
        final LegacyRegionScanner.WorldEvidence evidence;
        try {
            evidence = LegacyRegionScanner.scanWorldAgainstCandidate(
                worldRoot,
                resolution.migratedMin(),
                resolution.migratedMax(),
                resolution.legacyMin(),
                resolution.legacyMax()
            );
        } catch (IOException | RuntimeException e) {
            throw migrationFailure("could not safely inspect legacy world data against the candidate dense range", e);
        }

        resolution = LegacyWorldMigration.resolveWorldInspection(resolution, evidence);
        if (resolution.status() == LegacyWorldMigration.Status.REFUSE) {
            throw migrationFailure(resolution.reason(), null);
        }

        int[] migratedDense = new int[]{resolution.migratedMin(), resolution.migratedMax()};
        int[] dense = mergeRange(
            migratedDense[0], migratedDense[1], candidateDense[0], candidateDense[1]);
        System.err.println("Endless: safely classified pre-v0.4 world. Raw legacy config ["
            + raw.getMinBuildHeight() + ", " + raw.getMaxBuildHeight() + ") -> persisted dense core ["
            + dense[0] + ", " + dense[1] + "); logical v0.5 build range is ["
            + normalizedConfig.getMinBuildHeight() + ", " + normalizedConfig.getMaxBuildHeight() + ").");
        return dense;
    }

    private static IllegalStateException migrationFailure(String reason, Throwable cause) {
        String message = "Endless: REFUSING TO LOAD LEGACY WORLD - " + reason
            + " No chunks have been loaded by Endless at this migration gate. Back up the world before changing "
            + "anything; automatic startup is blocked to prevent irreversible section loss.";
        System.err.println(message);
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    /** Mirror the never-shrinking dense core into normal SavedData after levels exist. */
    public static void syncWorldData(MinecraftServer server) {
        EndlessWorldData data = server.overworld().getDataStorage()
            .computeIfAbsent(EndlessWorldData::load, EndlessWorldData::new, EndlessWorldData.DATA_NAME);
        if (data.getMinBuildHeight() != getDenseMinBuildHeight()
            || data.getMaxBuildHeight() != getDenseMaxBuildHeight()) {
            data.set(getDenseMinBuildHeight(), getDenseMaxBuildHeight());
        }
        data.setDirty();
    }

    /**
     * Read the persisted vanilla dense-core range. Existing but unreadable or
     * out-of-envelope data is a hard failure rather than an invitation to guess
     * a replacement layout from the current logical config.
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
                throw new IOException("persisted dense range file is missing MinBuildHeight/MaxBuildHeight");
            }

            int savedMin = data.getInt("MinBuildHeight");
            int savedMax = data.getInt("MaxBuildHeight");
            validateDenseRange(savedMin, savedMax);
            return new int[]{savedMin, savedMax};
        } catch (IOException | RuntimeException e) {
            throw migrationFailure("could not trust persisted dense build range at " + file + ": " + e.getMessage(), e);
        }
    }

    private static void validateDenseRange(int min, int max) {
        if (min < EndlessConfig.DENSE_MIN_BUILD_HEIGHT
            || max > EndlessConfig.DENSE_MAX_BUILD_HEIGHT
            || min >= max
            || Math.floorMod(min, 16) != 0
            || Math.floorMod(max, 16) != 0) {
            throw new IllegalArgumentException("persisted range [" + min + ", " + max
                + ") is not an aligned vanilla-safe dense range");
        }
    }

    /**
     * Force the vanilla baseline for every remote login connection. A local
     * memory connection belongs to the integrated server and shares the
     * already-authoritative server ranges in this JVM, so it is left alone.
     */
    public static void applyVanillaBaselineForNewConnection(boolean memoryConnection) {
        if (memoryConnection) {
            return;
        }
        applyEffective(
            VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT,
            VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT);
    }

    public static void applyVanillaBaselineIfUnapplied() {
        if (!applied) {
            applyEffective(
                VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT,
                VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT);
        }
    }

    /** Apply a logical range and use the fresh-v0.5 vanilla dense core (tests/local preseed). */
    public static void applyEffective(int min, int max) {
        EndlessConfig.BuildHeightConfig logical = normalizeLogicalRange(min, max);
        int[] dense = denseRangeForLogical(logical.getMinBuildHeight(), logical.getMaxBuildHeight());
        applyEffective(logical.getMinBuildHeight(), logical.getMaxBuildHeight(), dense[0], dense[1]);
    }

    /** Apply authoritative logical and dense ranges received from the server. */
    public static void applyEffective(int min, int max, int denseMin, int denseMax) {
        EndlessConfig.BuildHeightConfig logical = normalizeLogicalRange(min, max);
        validateDenseRange(denseMin, denseMax);
        effectiveMin = logical.getMinBuildHeight();
        effectiveMax = logical.getMaxBuildHeight();
        effectiveDenseMin = denseMin;
        effectiveDenseMax = denseMax;
        applied = true;
    }

    private static EndlessConfig.BuildHeightConfig normalizeLogicalRange(int min, int max) {
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        tmp.setMinBuildHeight(min);
        tmp.setMaxBuildHeight(max);
        tmp.clamp();
        if (tmp.getMinBuildHeight() != min || tmp.getMaxBuildHeight() != max) {
            throw new IllegalArgumentException("logical range [" + min + ", " + max
                + ") is outside the aligned v0.5 representation envelope");
        }
        return tmp;
    }

    public static void resetToLocalConfig() {
        applied = false;
    }
}
