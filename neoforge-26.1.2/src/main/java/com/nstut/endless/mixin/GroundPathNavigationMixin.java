package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.pathfinder.Path;

/** Normalize sparse targets locally; an empty column must not scan millions of blocks. */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin extends PathNavigation {
    protected GroundPathNavigationMixin(Mob mob, Level level) { super(mob, level); }

    @Inject(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"), cancellable = true)
    private void endless$rejectOutsideTarget(BlockPos target, int accuracy, CallbackInfoReturnable<Path> cir) {
        if (EndlessLogicalHeights.isActive() && EndlessHeights.isOutsideBuildHeight(target.getY())) {
            cir.setReturnValue(null);
        }
    }

    @Redirect(method = "findSurfacePosition",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinY()I"))
    private int endless$targetMin(Level level, LevelChunk chunk, BlockPos target, int reachRange) {
        if (!EndlessLogicalHeights.isActive()) return level.getMinY();
        // Match the navigator's local search radius, including its eight-block margin.
        int radius = (int) Math.ceil(mob.getAttributeValue(Attributes.FOLLOW_RANGE)) + 8;
        return Math.max(EndlessHeights.getMinBuildHeight(), target.getY() - radius);
    }

    @Redirect(method = "findSurfacePosition",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxY()I"))
    private int endless$targetMax(Level level, LevelChunk chunk, BlockPos target, int reachRange) {
        if (!EndlessLogicalHeights.isActive()) return level.getMaxY();
        int radius = (int) Math.ceil(mob.getAttributeValue(Attributes.FOLLOW_RANGE)) + 8;
        // Minecraft 26.1's getMaxY is inclusive; Endless' configured max remains exclusive.
        return Math.min(EndlessHeights.getMaxBuildHeight() - 1, target.getY() + radius);
    }
}
