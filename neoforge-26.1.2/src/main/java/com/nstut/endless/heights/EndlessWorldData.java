package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.nbt.CompoundTag;

/**
 * Version-neutral representation of Endless' persisted dense-core range.
 *
 * <p>Minecraft 26.1 namespaces SavedData files by identifier. Endless keeps its
 * historical data/endless_build_heights.dat path instead so the same world can
 * move between supported Minecraft lines without losing the dense-core record.</p>
 */
public final class EndlessWorldData {
    public static final String DATA_NAME = "endless_build_heights";

    private int minBuildHeight;
    private int maxBuildHeight;

    public EndlessWorldData() {
        minBuildHeight = EndlessHeights.VANILLA_MIN_BUILD_HEIGHT;
        maxBuildHeight = EndlessHeights.VANILLA_MAX_BUILD_HEIGHT;
    }

    public static EndlessWorldData load(CompoundTag tag) {
        EndlessWorldData data = new EndlessWorldData();
        if (!tag.contains("MinBuildHeight") || !tag.contains("MaxBuildHeight")) {
            throw new IllegalArgumentException("Persisted Endless dense range is missing bounds");
        }
        int min = tag.getIntOr("MinBuildHeight", Integer.MIN_VALUE);
        int max = tag.getIntOr("MaxBuildHeight", Integer.MAX_VALUE);
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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
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
