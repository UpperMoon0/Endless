package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin that modifies the min build height through the BelowZeroRetrogen's inner class.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.BelowZeroRetrogen$1")
public class BelowZeroRetrogenMixin {
    
    /**
     * @author Endless
     * @reason Modify minimum build height based on config
     
    @Inject(method = "getMinBuildHeight", at = @At("HEAD"), cancellable = true)
    private void getMinBuildHeight(CallbackInfoReturnable<Integer> cir) {
        // Replace the default minimum build height with our configured value
        cir.setReturnValue(EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight());
    }
    */
}
