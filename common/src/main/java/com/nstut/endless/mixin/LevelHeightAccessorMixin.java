package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {

    /**
     * @author Endless
     * @reason Use configured min build height
     */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
    }

    /**
     * @author Endless
     * @reason Use configured height for section allocation and Y-range calculations
     */
    @Overwrite
    default int getHeight() {
        EndlessConfig.BuildHeightConfig config = EndlessConfig.getInstance().getBuildHeight();
        int rawHeight = config.getMaxBuildHeight() - config.getMinBuildHeight();
        return Math.min(rawHeight, EndlessConfig.MAX_SECTIONS * 16);
    }

    /**
     * @author Endless
     * @reason Use configured build height bounds
     */
    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        int minHeight = getMinBuildHeight();
        return y < minHeight || y >= minHeight + getHeight();
    }

    /**
     * @author Endless
     * @reason Use configured build height bounds for block positions
     */
    @Overwrite
    default boolean isOutsideBuildHeight(net.minecraft.core.BlockPos blockPos) {
        return isOutsideBuildHeight(blockPos.getY());
    }
}
