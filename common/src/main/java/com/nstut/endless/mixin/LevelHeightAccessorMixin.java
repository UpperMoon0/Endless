package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {

    /** @author Endless @reason Keep the dense vanilla engine core bounded and persisted. */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessHeights.getMinBuildHeight();
    }

    /** @author Endless @reason Keep vanilla section arrays bounded to the v0.4 core. */
    @Overwrite
    default int getHeight() {
        return EndlessHeights.getHeight();
    }

    /**
     * @author Endless
     * @reason Buildability is wider than the dense engine core when the sparse protocol is active.
     */
    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        return EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.isOutsideBuildHeight(y)
            : EndlessHeights.isOutsideBuildHeight(y);
    }

    /** @author Endless @reason Apply the logical buildability test to positions. */
    @Overwrite
    default boolean isOutsideBuildHeight(BlockPos blockPos) {
        return isOutsideBuildHeight(blockPos.getY());
    }
}
