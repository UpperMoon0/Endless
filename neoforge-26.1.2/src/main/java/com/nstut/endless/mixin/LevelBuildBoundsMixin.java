package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Give real worlds explicit logical bounds instead of relying on inherited
 * interface defaults. Iris's 26.1.2 startup can leave LevelHeightAccessor's
 * defaults untransformed, while Level itself still receives its mixins.
 * Dense chunk/generation accessors continue to use their physical bounds.
 */
@Mixin(Level.class)
public abstract class LevelBuildBoundsMixin implements LevelHeightAccessor {
    @Override public boolean isInsideBuildHeight(int y) {
        if (EndlessLogicalHeights.isActive()) return !EndlessHeights.isOutsideBuildHeight(y);
        return y >= getMinY() && y < getMinY() + getHeight();
    }
    @Override public boolean isInsideBuildHeight(BlockPos pos) { return isInsideBuildHeight(pos.getY()); }
    @Override public boolean isOutsideBuildHeight(int y) { return !isInsideBuildHeight(y); }
    @Override public boolean isOutsideBuildHeight(BlockPos pos) { return isOutsideBuildHeight(pos.getY()); }
}
