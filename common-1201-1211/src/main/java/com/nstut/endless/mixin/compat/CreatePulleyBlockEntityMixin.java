package com.nstut.endless.mixin.compat;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve Create's rope limit while allowing travel below the dense core. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity", remap = false)
public abstract class CreatePulleyBlockEntityMixin {
    @Redirect(method = {"getExtensionRange", "getMinValue"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"), require = 1)
    private int endless$logicalMinimum(Level level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMinBuildHeight() : level.getMinBuildHeight();
    }
}
