package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
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
 *       ServerAboutToStart) and reads the range persisted in the world save
 *       directly from disk, so chunk deserialization sees the world's range,
 *       not a possibly-shrunk config. {@link #syncWorldData} then mirrors the
 *       effective range into the regular SavedData store after worlds load.
 *       The range only ever widens, so saved sections above and below the
 *       current world stay addressable.</li>
 *   <li>Remote clients: begin every remote connection with the vanilla range
 *       and adopt the server's range during the login phase only if the
 *       server provides one (Fabric via {@code ServerLoginNetworking}, Forge
 *       via a Forge login packet). The baseline is established at the start
 *       of the login phase ({@code ClientHandshakePacketListenerImpl
 *       .handleHello}) via {@link #applyVanillaBaselineForNewConnection}, and
 *       re-established defensively at the login packet via
 *       {@link #applyVanillaBaselineIfUnapplied}, so neither an extended
 *       local file config nor a range left over from a previous connection
 *       can leak into a world (a login-stage rejection may skip the
 *       disconnect hooks entirely, so a stale applied range must be cleared
 *       when the next connection begins rather than only when one ends).</li>
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
     * Apply the world's persisted range. Called before any ServerLevel is
     * created: the saved {@code data/endless_build_heights.dat} is read raw
     * from the world root, because the overworld's SavedData storage only
     * becomes available after the world exists — too late for chunk
     * deserialization, which uses the effective range to size section arrays.
     */
    public static void loadPersistedRange(MinecraftServer server) {
        int[] saved = readSavedRange(server);
        EndlessConfig.BuildHeightConfig cfg = clampedFileConfig();
        int[] merged;
        if (saved == null) {
            merged = new int[]{cfg.getMinBuildHeight(), cfg.getMaxBuildHeight()};
            warnIfPrePersistenceWorld(server, cfg);
        } else {
            merged = mergeRange(saved[0], saved[1], cfg.getMinBuildHeight(), cfg.getMaxBuildHeight());
            if (cfg.getMinBuildHeight() > saved[0] || cfg.getMaxBuildHeight() < saved[1]) {
                System.err.println("Endless: config range [" + cfg.getMinBuildHeight() + ", " + cfg.getMaxBuildHeight()
                    + ") is narrower than this world's persisted range [" + saved[0] + ", " + saved[1]
                    + "); keeping the wider world range. Saved chunks would be unreachable otherwise.");
            }
        }
        applyEffective(merged[0], merged[1]);
    }

    /**
     * First-launch ambiguity for pre-v0.4 worlds: versions before the world
     * persistence existed wrote no {@code endless_build_heights.dat}, so
     * Endless cannot know which range an existing world was actually played
     * with. If the config has since been shrunk, chunks saved outside the new
     * range are unreachable and the older section data would be mapped onto
     * the wrong Y positions. The situation is unfixable from the mod side
     * (there is no record to recover), so warn loudly instead of failing
     * silently.
     *
     * <p>Only fires for worlds with actual played history: {@code level.dat}
     * already exists for a brand-new world by the time the server starts, so
     * the warning keys on chunk region files instead — a genuinely new world
     * has written none at this point (spawn chunks are generated only after
     * this hook).</p>
     */
    private static void warnIfPrePersistenceWorld(MinecraftServer server, EndlessConfig.BuildHeightConfig cfg) {
        try {
            Path regionDir = server.getWorldPath(new LevelResource("region"));
            if (!hasRegionData(regionDir)) {
                return;
            }
            System.err.println("Endless: MIGRATION WARNING - this world was created before Endless persisted its "
                + "build range (no data/" + EndlessWorldData.DATA_NAME + ".dat found), so it may have been played "
                + "with a different range than the current config [" + cfg.getMinBuildHeight() + ", "
                + cfg.getMaxBuildHeight() + "). If this world previously used a wider range, set the config back "
                + "to that wider range BEFORE generating or loading chunks, or saved sections outside the range "
                + "become unreachable.");
        } catch (RuntimeException e) {
            // Never let the warning path break startup.
        }
    }

    /**
     * Played-world evidence: at least one chunk region file. An unplayed
     * world has no {@code region/*.mca} yet, which keeps brand-new worlds
     * silent even though their {@code level.dat} already exists.
     */
    private static boolean hasRegionData(Path regionDir) {
        if (!Files.isDirectory(regionDir)) {
            return false;
        }
        try (var stream = Files.list(regionDir)) {
            return stream.anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.startsWith("r.") && name.endsWith(".mca");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Mirror the effective range into the regular SavedData store so it is
     * checkpointed with the world save. Call after worlds are loaded; reading
     * at startup is handled by {@link #loadPersistedRange}.
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

    private static int[] readSavedRange(MinecraftServer server) {
        try {
            // getWorldPath: the overworld lives at the world root, and the
            // range must be readable before any ServerLevel (and its
            // DimensionDataStorage) exists.
            Path file = server.getWorldPath(new LevelResource("data"))
                .resolve(EndlessWorldData.DATA_NAME + ".dat");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            CompoundTag root = NbtIo.readCompressed(file.toFile());
            CompoundTag data = root.getCompound("data");
            if (!data.contains("MinBuildHeight") || !data.contains("MaxBuildHeight")) {
                return null;
            }
            EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
            tmp.setMinBuildHeight(data.getInt("MinBuildHeight"));
            tmp.setMaxBuildHeight(data.getInt("MaxBuildHeight"));
            tmp.clamp();
            return new int[]{tmp.getMinBuildHeight(), tmp.getMaxBuildHeight()};
        } catch (IOException | RuntimeException e) {
            System.err.println("Endless: could not read persisted build range ("
                + e.getMessage() + "); falling back to file config");
            return null;
        }
    }

    /**
     * Start a new client connection on the vanilla build range. Called from
     * the login-phase start ({@code ClientHandshakePacketListenerImpl
     * .handleHello}), which every connection passes through before either
     * loader's Endless login exchange, so the reset cannot be skipped by a
     * mid-login rejection: a range applied during a previous connection's
     * login handshake (where the player never existed and the logout hook
     * never ran) must not survive into the next connection, where the
     * {@code !applied} guard would wrongly treat it as authoritative.
     *
     * <p>Singleplayer is excluded: the integrated server owns the effective
     * range and has already applied the world's persisted range before the
     * client's login phase starts, so the shared static must not be
     * clobbered. The server's range still overwrites this baseline during
     * login whenever the Endless login exchange runs.</p>
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
     * applied. Remote logins must never enter the world on the local file
     * config: a server without Endless (both loaders accept the connection)
     * or an Endless server with a vanilla world range (Fabric sends no login
     * query for it) delivers no authoritative range, and a client with an
     * extended local config would otherwise size its section arrays for a
     * layout the server never uses. Singleplayer is unaffected: the
     * integrated/dedicated server calls {@link #loadPersistedRange} before
     * the client login completes, so {@code applied} is already true and
     * this method is a no-op there.
     *
     * <p>Kept as a final fallback behind
     * {@link #applyVanillaBaselineForNewConnection}: called from the client
     * login-packet hook, immediately before the client world is constructed
     * and strictly after any Endless login-phase sync has run.</p>
     */
    public static void applyVanillaBaselineIfUnapplied() {
        if (!applied) {
            applyEffective(VANILLA_MIN_BUILD_HEIGHT, VANILLA_MAX_BUILD_HEIGHT);
        }
    }

    /**
     * Apply an authoritative range (from world data or a server sync packet).
     * Values are snapped to section boundaries and clamped to the envelope.
     */
    public static void applyEffective(int min, int max) {
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        tmp.setMinBuildHeight(min);
        tmp.setMaxBuildHeight(max);
        tmp.clamp();
        effectiveMin = tmp.getMinBuildHeight();
        effectiveMax = tmp.getMaxBuildHeight();
        applied = true;
    }

    /**
     * Drop the applied range so the local file config is used again; called on
     * client disconnect before joining another server.
     */
    public static void resetToLocalConfig() {
        applied = false;
    }
}
