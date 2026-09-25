package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.mixin.accessor.WalkNodeEvaluatorAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BlockPos.asLong only preserves 12 Y bits. Sparse positions outside that
 * envelope must not share ServerLevel's global path-type cache with their
 * wrapped vanilla-range aliases.
 */
@Mixin(PathTypeCache.class)
public abstract class PathTypeCacheMixin {
    @Inject(method = "getOrCompute", at = @At("HEAD"), cancellable = true)
    private void endless$bypassPackedYAlias(
        BlockGetter level,
        BlockPos pos,
        CallbackInfoReturnable<PathType> cir
    ) {
        if (EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())) {
            cir.setReturnValue(WalkNodeEvaluatorAccessor.endless$invokeGetPathTypeFromState(level, pos));
        }
    }
}
