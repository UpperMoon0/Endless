package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import com.nstut.endless.network.EndlessNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
 *   <li>Remote clients: received from the server during the login phase on
 *       Fabric (via {@code ServerLoginNetworking}) and during the play phase
 *       on Forge (via {@code PlayerListMixin}); reset to the local config on
 *       disconnect.</li>
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

    /**
     * Deliver the authoritative range to a joining player. Loader dispatch:
     *
     * <ul>
     *   <li>Fabric has a login-phase handshake installed; the play-phase
     *       call here is a no-op because {@link EndlessNetworking#shouldEnforceRange}
     *       returns false. The range has already been applied on the client
     *       during login, and vanilla clients have already been disconnected
     *       by the login pipeline.</li>
     *   <li>Forge delivers the range in the play phase right before
     *       {@code sendLevelInfo}. If the joining client has the play channel
     *       registered we send the sync; if it does not and the range is
     *       extended we disconnect it, because section payloads on the wire
     *       carry no Y coordinates and would be mapped to the wrong Y
     *       positions on a vanilla client.</li>
     * </ul>
     */
    public static void syncOnJoin(ServerPlayer player) {
        if (!EndlessNetworking.shouldEnforceRange(player)) {
            return;
        }
        int min = getMinBuildHeight();
        int max = getMaxBuildHeight();
        if (EndlessNetworking.canSend(player)) {
            EndlessNetworking.sendHeights(player, min, max);
        } else if (min != VANILLA_MIN_BUILD_HEIGHT || max != VANILLA_MAX_BUILD_HEIGHT) {
            player.connection.disconnect(Component.literal(
                "This server requires the Endless mod: its build range ["
                    + min + ", " + max + ") is extended beyond vanilla."));
        }
    }
}
