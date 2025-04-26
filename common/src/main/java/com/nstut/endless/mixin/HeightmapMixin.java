package com.nstut.endless.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the game from crashing when building too high by preventing the Heightmap from updating
 * with values beyond what SimpleBitStorage can handle (0-511).
 */
@Mixin(Heightmap.class)
public abstract class HeightmapMixin {
    
    @Shadow protected abstract void setHeight(int x, int y, int z);
    
    /**
     * Intercept the update method to check if the Y-coordinate is within the valid range
     * before attempting to update the heightmap.
     */    
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void onUpdate(int x, int y, int z, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        // The heightmap can only store values from 0-511
        // If y is outside this range, cancel the update to prevent crashes
        if (y < 0 || y > 511) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
