package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {

    /**
     * @author Endless
     * @reason Use the effective (world-persisted or synced) min build height
     */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessHeights.getMinBuildHeight();
    }

    /**
     * @author Endless
     * @reason Use effective height for section allocation and Y-range calculations
     */
    @Overwrite
    default int getHeight() {
        return EndlessHeights.getHeight();
    }

    /**
     * @author Endless
     * @reason Use effective build height bounds
     */
    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        return EndlessHeights.isOutsideBuildHeight(y);
    }

    /**
     * @author Endless
     * @reason Use effective build height bounds for block positions
     */
    @Overwrite
    default boolean isOutsideBuildHeight(BlockPos blockPos) {
        return isOutsideBuildHeight(blockPos.getY());
    }
}
