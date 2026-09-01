package com.nstut.endless.heights;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-persisted build range. The range defines the chunk section array
 * layout, so it must survive config edits: on load it is merged with the file
 * config (never shrinking), making the effective range world-stable while
 * still allowing deliberate expansion.
 */
public class EndlessWorldData extends SavedData {
    public static final String DATA_NAME = "endless_build_heights";

    private int minBuildHeight;
    private int maxBuildHeight;

    /** Fresh data: seed from the current file config. */
    public EndlessWorldData() {
        EndlessConfig.BuildHeightConfig cfg = EndlessConfig.getInstance().getBuildHeight();
        minBuildHeight = cfg.getMinBuildHeight();
        maxBuildHeight = cfg.getMaxBuildHeight();
    }

    public static EndlessWorldData load(CompoundTag tag) {
        EndlessWorldData data = new EndlessWorldData();
        data.minBuildHeight = tag.getInt("MinBuildHeight");
        data.maxBuildHeight = tag.getInt("MaxBuildHeight");
        // Old or hand-edited data may be outside the envelope; normalize.
        EndlessConfig.BuildHeightConfig tmp = new EndlessConfig.BuildHeightConfig();
        tmp.setMinBuildHeight(data.minBuildHeight);
        tmp.setMaxBuildHeight(data.maxBuildHeight);
        tmp.clamp();
        data.minBuildHeight = tmp.getMinBuildHeight();
        data.maxBuildHeight = tmp.getMaxBuildHeight();
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
