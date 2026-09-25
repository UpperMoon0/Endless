package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.PathNavigationRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A navigation region has an X/Z chunk array, never a dense vertical section array. */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin {
    @Inject(method = "getMinBuildHeight", at = @At("HEAD"), cancellable = true)
    private void endless$logicalMin(CallbackInfoReturnable<Integer> cir) {
        if (EndlessLogicalHeights.isActive()) cir.setReturnValue(EndlessHeights.getMinBuildHeight());
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void endless$logicalHeight(CallbackInfoReturnable<Integer> cir) {
        if (EndlessLogicalHeights.isActive()) {
            cir.setReturnValue(EndlessHeights.getMaxBuildHeight() - EndlessHeights.getMinBuildHeight());
        }
    }
}
