package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native pistons retain native push rules, with logical world boundary checks. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @Redirect(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private static int endless$pushMin(Level level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMinBuildHeight() : level.getMinBuildHeight();
    }
    @Redirect(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private static int endless$pushMax(Level level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMaxBuildHeight() : level.getMaxBuildHeight();
    }
}
