package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-persisted vanilla dense-core range.
 *
 * <p>This is intentionally not the user-configured logical build limit. The
 * dense range survives config shrink so vanilla Anvil sections are never
 * dropped merely because the current sparse build envelope is narrower.</p>
 */
public class EndlessWorldData extends SavedData {
    public static final String DATA_NAME = "endless_build_heights";

    private int minBuildHeight;
    private int maxBuildHeight;

    /** Fresh data: seed the dense core from the current logical config. */
    public EndlessWorldData() {
        EndlessConfig.BuildHeightConfig cfg = EndlessConfig.getInstance().getBuildHeight();
        int[] dense = EndlessHeights.denseRangeForLogical(
            cfg.getMinBuildHeight(), cfg.getMaxBuildHeight());
        minBuildHeight = dense[0];
        maxBuildHeight = dense[1];
    }

    public static EndlessWorldData load(CompoundTag tag) {
        EndlessWorldData data = new EndlessWorldData();
        int min = tag.getInt("MinBuildHeight");
        int max = tag.getInt("MaxBuildHeight");
        if (min < EndlessConfig.DENSE_MIN_BUILD_HEIGHT
            || max > EndlessConfig.DENSE_MAX_BUILD_HEIGHT
            || min >= max
            || Math.floorMod(min, 16) != 0
            || Math.floorMod(max, 16) != 0) {
            throw new IllegalArgumentException("Invalid persisted Endless dense range [" + min + ", " + max + ")");
        }
        data.minBuildHeight = min;
        data.maxBuildHeight = max;
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("MinBuildHeight", minBuildHeight);
        tag.putInt("MaxBuildHeight", maxBuildHeight);
        return tag;
    }

    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    public int getMaxBuildHeight() {
        return maxBuildHeight;
    }

    public void set(int minBuildHeight, int maxBuildHeight) {
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
    }
}
