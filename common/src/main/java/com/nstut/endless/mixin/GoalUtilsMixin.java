package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes vanilla random-position AI respect the logical rather than dense Y bounds. */
@Mixin(GoalUtils.class)
public abstract class GoalUtilsMixin {
    @Inject(method = "isOutsideLimits", at = @At("HEAD"), cancellable = true)
    private static void endless$isOutsideLimits(
        BlockPos pos,
        PathfinderMob mob,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (EndlessLogicalHeights.isActive()) {
            cir.setReturnValue(EndlessLogicalHeights.isOutsideBuildHeight(pos.getY()));
        }
    }
}
