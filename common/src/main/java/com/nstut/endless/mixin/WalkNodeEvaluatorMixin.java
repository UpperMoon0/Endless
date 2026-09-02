package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Extends the walk-node ground search down to Endless' logical floor. */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Redirect(
        method = "getStart",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int endless$logicalMinBuildHeight(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.MIN_BUILD_HEIGHT
            : level.getMinBuildHeight();
    }
}
