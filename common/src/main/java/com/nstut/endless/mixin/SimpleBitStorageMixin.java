package com.nstut.endless.mixin;

import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents crashes in SimpleBitStorage when values outside the allowed range are used.
 * This is a safeguard in case the HeightmapMixin isn't enough.
 */
@Mixin(SimpleBitStorage.class)
public class SimpleBitStorageMixin {
    
    @Shadow private long mask;
    
    /**
     * Catches attempts to set values outside the allowed range and ignores them
     * rather than throwing an exception that crashes the game.
     */
    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private void onSet(int index, int value, CallbackInfo ci) {
        // If the value is outside the allowed range, just ignore it
        // This prevents the IllegalArgumentException that crashes the game
        if (value < 0 || value > this.mask) {
            ci.cancel();
        }
    }
}
