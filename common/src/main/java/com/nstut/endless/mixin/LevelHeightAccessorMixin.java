package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin that modifies the build height limits of the world.
 */
@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {
      /**
     * @author Endless
     * @reason Remove build height limit
     */
    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        if (!EndlessConfig.getInstance().getBuildHeight().isRemoveBuildHeightLimit()) {
            int minHeight = ((LevelHeightAccessor) this).getMinBuildHeight();
            int maxHeight = getMaxBuildHeight();
            return y < minHeight || y >= maxHeight;
        }
        return false; // When limits are removed, no position is outside build height
    }
    
    /**
     * @author Endless
     * @reason Remove build height limit for block positions
     */
    @Overwrite
    default boolean isOutsideBuildHeight(net.minecraft.core.BlockPos blockPos) {
        return isOutsideBuildHeight(blockPos.getY());
    }
    
    /**
     * @author Endless
     * @reason Modify maximum build height based on config
     */
    @Overwrite
    default int getMaxBuildHeight() {
        return EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight();
    }
}
