package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents dense-core minimum Y from disabling mob paths in the lower sparse world. */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Redirect(
        method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int endless$logicalMinBuildHeight(Level level) {
        return EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.MIN_BUILD_HEIGHT
            : level.getMinBuildHeight();
    }
}
