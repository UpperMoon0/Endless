package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(LevelHeightAccessor.class)
public interface LevelHeightAccessorMixin {

    /** @author Endless @reason Keep vanilla section-array origin on the persisted dense core. */
    @Overwrite
    default int getMinBuildHeight() {
        return EndlessHeights.getDenseMinBuildHeight();
    }

    /** @author Endless @reason Keep vanilla section arrays bounded to the persisted dense core. */
    @Overwrite
    default int getHeight() {
        return EndlessHeights.getHeight();
    }

    /**
     * @author Endless
     * @reason Real worlds expose the user-configured logical build limit, while
     * dense generation/chunk accessors must retain the persisted dense guard
     * before indexing their bounded section arrays.
     */
    @Overwrite
    default boolean isOutsideBuildHeight(int y) {
        if (EndlessLogicalHeights.isActive() && (Object) this instanceof Level) {
            return EndlessHeights.isOutsideBuildHeight(y);
        }
        return EndlessHeights.isOutsideDenseBuildHeight(y);
    }

    /** @author Endless @reason Apply the accessor-appropriate buildability test to positions. */
    @Overwrite
    default boolean isOutsideBuildHeight(BlockPos blockPos) {
        return isOutsideBuildHeight(blockPos.getY());
    }
}
