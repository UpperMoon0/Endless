package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateLogicalGeometry;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Buildability is logical; Create's explicit client check must not use dense array bounds. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity", remap = false)
public abstract class CreateArmBlockEntityMixin {
    @Redirect(method = "isAreaActuallyLoaded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isAreaLoaded(Lnet/minecraft/core/BlockPos;I)Z"), require = 1)
    private boolean endless$loadedLogicalArea(Level level, BlockPos center, int range) {
        return CreateLogicalGeometry.isAreaLoaded(level, center, range);
    }
    @Redirect(method = "isAreaActuallyLoaded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"), require = 1)
    private int endless$logicalMinimum(Level level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMinBuildHeight() : level.getMinBuildHeight();
    }
    @Redirect(method = "isAreaActuallyLoaded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"), require = 1)
    private int endless$logicalMaximum(Level level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMaxBuildHeight() : level.getMaxBuildHeight();
    }
}
