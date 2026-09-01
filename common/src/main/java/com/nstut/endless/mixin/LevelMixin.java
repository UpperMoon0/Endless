package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Overlays sparse high-Y blocks onto vanilla height queries without widening BitStorage. */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true)
    private void endless$getHeight(
        Heightmap.Types type,
        int x,
        int z,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (!EndlessLogicalHeights.isActive()) {
            return;
        }
        Level self = (Level) (Object) this;
        int sparse = EndlessVerticalEngine.world(self).getExtendedHeight(type, x, z);
        if (sparse != Integer.MIN_VALUE && sparse > cir.getReturnValue()) {
            cir.setReturnValue(sparse);
        }
    }
}
