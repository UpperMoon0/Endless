package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Extends flying-node floor classification into the configured lower sparse world. */
@Mixin(FlyNodeEvaluator.class)
public abstract class FlyNodeEvaluatorMixin {
    @Redirect(
        method = "getBlockPathType(Lnet/minecraft/world/level/BlockGetter;III)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getMinBuildHeight()I"))
    private int endless$logicalMinBuildHeight(BlockGetter level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinBuildHeight();
    }
}
