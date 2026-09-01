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
 *       deserialize: safe legacy ranges migrate, raw edge sections are scanned
 *       for meaningful data, and ambiguous/unrepresentable histories fail
 *       closed instead of silently dropping sections.</li>
 *   <li>Remote clients: begin every remote connection with the vanilla range
 *       and adopt the server's range during the login phase only if the
 *       server provides one (Fabric via {@code ServerLoginNetworking}, Forge
 *       via a Forge login packet). The baseline is established at the start
 *       of the login phase ({@code ClientHandshakePacketListenerImpl
 *       .handleHello}) via {@link #applyVanillaBaselineForNewConnection}, and
 *       re-established defensively at the login packet via
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

    /**
     * Merge the world-persisted range with the config range. Expansion is
     * allowed; shrinking is rejected so saved sections never become
     * unreachable. Pure: no side effects, unit-testable.
     */
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
     * ServerLevel is created. This ordering is mandatory: vanilla
     * ChunkSerializer allocates the section array from the current
     * LevelHeightAccessor and skips saved sections whose absolute signed-byte Y
     * falls outside that array. Continuing with an untrusted narrower range can
     * therefore permanently delete legacy sections on the next save.
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

        // Only now is it safe to rewrite a clamped/aligned config. Before this
        // point the raw file may be the only evidence of a pre-v0.4 world's
        // historical range.
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

        if (resolution.status() == LegacyWorldMigration.Status.INSPECT_EDGE_SECTIONS) {
            final LegacyRegionScanner.EdgeUsage edgeUsage;
            try {
                edgeUsage = LegacyRegionScanner.scanEdgeSections(
                    worldRoot, resolution.inspectBottomEdge(), resolution.inspectTopEdge());
            } catch (IOException e) {
                throw migrationFailure("could not safely inspect legacy edge sections", e);
            }
            resolution = LegacyWorldMigration.resolveEdgeInspection(
                resolution,
                edgeUsage.bottomHasMeaningfulData(),
                edgeUsage.topHasMeaningfulData()
            );
            if (resolution.status() == LegacyWorldMigration.Status.REFUSE) {
                throw migrationFailure(resolution.reason(), null);
            }
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

    /**
     * Mirror the effective range into the regular SavedData store so it is
     * checkpointed with the world save. Call after worlds are loaded; reading
     * and legacy classification at startup are handled by
     * {@link #loadPersistedRange}.
     */
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
     * Read v0.4+ world metadata. An existing but unreadable/out-of-envelope
     * range is a hard failure: treating it as "missing" would re-enter legacy
     * migration and could replace authoritative world layout metadata with the
     * current file config.
     */
    private static int[] readSavedRange(MinecraftServer server) {
        Path file = server.getWorldPath(new LevelResource("data"))
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
     * Start a new client connection on the vanilla build range. Called from
     * the login-phase start ({@code ClientHandshakePacketListenerImpl
     * .handleHello}), which every connection passes through before either
     * loader's Endless login exchange, so the reset cannot be skipped by a
     * mid-login rejection.
     *
     * @param singleplayer true when this connection is to an integrated
     *                     server in the same JVM
     */
    public static void applyVanillaBaselineForNewConnection(boolean singleplayer) {
        if (singleplayer) {
            return;
        }
        applyEffective(VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT);
    }

    /**
     * Force the vanilla build range when no authoritative range has been
     * applied. Kept as a final fallback behind
     * {@link #applyVanillaBaselineForNewConnection}: called from the client
     * login-packet hook immediately before the client world is constructed and
     * strictly after any Endless login-phase sync has run.
     */
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

    /** Drop the applied client range so the next connection re-establishes it. */
    public static void resetToLocalConfig() {
        applied = false;
    }
}
