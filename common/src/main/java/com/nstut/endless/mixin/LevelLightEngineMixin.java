package com.nstut.endless.mixin;

import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {

    private static final int MAX_LIGHT_SECTION = 64;
    private static final int MIN_LIGHT_SECTION = -64;

    @Inject(method = "getMinLightSection", at = @At("RETURN"), cancellable = true)
    private void clampMinLightSection(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.max(cir.getReturnValue(), MIN_LIGHT_SECTION));
    }

    @Inject(method = "getMaxLightSection", at = @At("RETURN"), cancellable = true)
    private void clampMaxLightSection(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.min(cir.getReturnValue(), MAX_LIGHT_SECTION));
    }
}
