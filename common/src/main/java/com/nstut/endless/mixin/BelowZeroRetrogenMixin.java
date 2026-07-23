package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.level.levelgen.BelowZeroRetrogen$1")
public class BelowZeroRetrogenMixin {

    @Inject(method = "getMinBuildHeight", at = @At("HEAD"), cancellable = true)
    private void getMinBuildHeight(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight());
    }
}
