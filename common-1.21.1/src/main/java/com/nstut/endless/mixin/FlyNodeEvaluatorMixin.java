package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Extends flying-node floor classification into the configured lower sparse world. */
@Mixin(FlyNodeEvaluator.class)
public abstract class FlyNodeEvaluatorMixin {
    @Redirect(
        method = "getPathType",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/CollisionGetter;getMinBuildHeight()I"
        )
    )
    private int endless$logicalMinBuildHeight(CollisionGetter level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinBuildHeight();
    }
}
