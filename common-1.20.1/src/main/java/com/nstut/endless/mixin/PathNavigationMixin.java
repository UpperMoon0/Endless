package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.HashSet;
import java.util.Set;

/** Prevents dense-core minimum Y from disabling mob paths in the configured lower sparse world. */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @ModifyVariable(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Set<BlockPos> endless$legalTargets(Set<BlockPos> targets) {
        if (!EndlessLogicalHeights.isActive()
            || targets.stream().noneMatch(pos -> EndlessHeights.isOutsideBuildHeight(pos.getY()))) {
            return targets;
        }
        Set<BlockPos> legal = new HashSet<>(targets);
        legal.removeIf(pos -> EndlessHeights.isOutsideBuildHeight(pos.getY()));
        return legal;
    }

    @Redirect(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int endless$logicalMinBuildHeight(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessHeights.getMinBuildHeight()
            : level.getMinBuildHeight();
    }
}
