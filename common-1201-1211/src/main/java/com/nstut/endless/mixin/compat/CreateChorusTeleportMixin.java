package com.nstut.endless.mixin.compat;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep native randomness, loader teleport events, collision checks and effects. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.equipment.potatoCannon.AllPotatoProjectileEntityHitActions$ChorusTeleport", remap = false)
public abstract class CreateChorusTeleportMixin {
    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D", remap = true), require = 1)
    private double endless$logicalClamp(double y, double min, double max, ItemStack projectile, EntityHitResult ray, @Coerce Object type) {
        if (!EndlessLogicalHeights.isActive()) return net.minecraft.util.Mth.clamp(y, min, max);
        return net.minecraft.util.Mth.clamp(y, EndlessHeights.getMinBuildHeight(), EndlessHeights.getMaxBuildHeight() - 1);
    }
}
