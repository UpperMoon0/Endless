package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends walk-node classification/searches through Endless' logical sparse range. */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(
        method = "getPathTypeStatic(Lnet/minecraft/world/level/pathfinder/PathfindingContext;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void endless$blockOutsideNodes(
        PathfindingContext context,
        BlockPos.MutableBlockPos pos,
        CallbackInfoReturnable<PathType> cir
    ) {
        if (EndlessLogicalHeights.isActive() && EndlessHeights.isOutsideBuildHeight(pos.getY())) {
            cir.setReturnValue(PathType.BLOCKED);
        }
    }

    @Redirect(
        method = "getStart",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/CollisionGetter;getMinY()I"
        )
    )
    private int endless$logicalStartMin(CollisionGetter level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinY();
    }

    @Redirect(
        method = {"tryFindFirstNonWaterBelow", "tryFindFirstGroundNodeBelow"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getMinY()I"
        )
    )
    private int endless$logicalSearchMin(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinY();
    }

    @Redirect(
        method = "getPathTypeStatic(Lnet/minecraft/world/level/pathfinder/PathfindingContext;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/CollisionGetter;getMinY()I"
        )
    )
    private static int endless$classificationMin(CollisionGetter level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinY();
    }
}
