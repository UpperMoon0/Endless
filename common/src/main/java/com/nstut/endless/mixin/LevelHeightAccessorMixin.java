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
        return config.getMaxBuildHeight() - config.getMinBuildHeight();
    }

    /**
     * @author Endless
     * @reason Use configured build height bounds
     */
    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        int minHeight = EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
        int maxHeight = EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight();
        return y < minHeight || y >= maxHeight;
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
