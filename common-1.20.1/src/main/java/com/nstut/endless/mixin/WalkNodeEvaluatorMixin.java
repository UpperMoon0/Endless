package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends the walk-node ground search down to Endless' configured logical floor. */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getBlockPathTypeRaw", at = @At("HEAD"), cancellable = true)
    private static void endless$blockOutsideNodes(BlockGetter level, BlockPos pos,
                                                  CallbackInfoReturnable<BlockPathTypes> cir) {
        if (EndlessLogicalHeights.isActive() && EndlessHeights.isOutsideBuildHeight(pos.getY())) {
            cir.setReturnValue(BlockPathTypes.BLOCKED);
        }
    }

    @Redirect(
        method = {"getStart", "findAcceptedNode"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int endless$logicalMinBuildHeight(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinBuildHeight();
    }

    @Redirect(
        method = "getBlockPathTypeStatic",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getMinBuildHeight()I"))
    private static int endless$classificationMin(BlockGetter level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight() : level.getMinBuildHeight();
    }
}
