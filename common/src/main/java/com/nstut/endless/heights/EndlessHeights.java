package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import com.nstut.endless.network.EndlessNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Runtime holder for the effective build range. The file config expresses user
 * intent; the effective range is what the world actually uses:
 *
 * <ul>
 *   <li>Dedicated/integrated servers: merged from the config and the
 *       world-persisted range ({@link EndlessWorldData}) when the server
 *       starts. The range only ever widens, so saved chunks above and below
 *       the current world stay addressable.</li>
 *   <li>Remote clients: received from the server before any chunk packet
 *       (see {@code PlayerListMixin}); resets to the local config on
 *       disconnect.</li>
 * </ul>
 */
public final class EndlessHeights {

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

    /**
     * Load or create the world's persisted range, merge it with the file
     * config (never shrinking), apply the result and persist it. Call once
     * per server start, after worlds exist and before players join.
     */
    public static void applyWorldRange(MinecraftServer server) {
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        tmp.setMinBuildHeight(EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight());
        tmp.setMaxBuildHeight(EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight());
        tmp.clamp();

        EndlessWorldData data = server.overworld().getDataStorage()
            .computeIfAbsent(EndlessWorldData::load, EndlessWorldData::new, EndlessWorldData.DATA_NAME);

        int savedMin = data.getMinBuildHeight();
        int savedMax = data.getMaxBuildHeight();
        int[] merged = mergeRange(savedMin, savedMax, tmp.getMinBuildHeight(), tmp.getMaxBuildHeight());

        if (tmp.getMinBuildHeight() > savedMin || tmp.getMaxBuildHeight() < savedMax) {
            System.err.println("Endless: config range [" + tmp.getMinBuildHeight() + ", " + tmp.getMaxBuildHeight()
                + ") is narrower than this world's persisted range [" + savedMin + ", " + savedMax
                + "); keeping the wider world range. Saved chunks would be unreachable otherwise.");
        }
        if (merged[0] != savedMin || merged[1] != savedMax) {
            data.set(merged[0], merged[1]);
        }
        // Always dirty so a fresh (never-saved) range gets written out.
        data.setDirty();
        applyEffective(merged[0], merged[1]);
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

    /** Push the effective range to a client during login, before chunk packets. */
    public static void sendToPlayer(ServerPlayer player) {
        EndlessNetworking.sendHeights(player, getMinBuildHeight(), getMaxBuildHeight());
    }
}
