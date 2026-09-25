package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves the vanilla below-world kill plane to Endless' configured logical lower bound. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
    private void endless$checkBelowWorld(CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) {
            return;
        }
        Entity self = (Entity) (Object) this;
        if (self.getY() >= (double) EndlessHeights.getMinBuildHeight() - 64.0D) {
            ci.cancel();
        }
    }
}
