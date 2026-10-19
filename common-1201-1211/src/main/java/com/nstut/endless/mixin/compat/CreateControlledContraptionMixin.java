package com.nstut.endless.mixin.compat;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageLayout;
import com.nstut.endless.vertical.VerticalPagePos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A horizontal chunk can arrive before a distant sparse controller's page. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public abstract class CreateControlledContraptionMixin {
    @Redirect(method = {"tickContraption", "getController"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;isLoaded(Lnet/minecraft/core/BlockPos;)Z", remap = true), require = 1)
    private boolean endless$controllerPageReady(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        if (!level.isClientSide || !EndlessVerticalEngine.isExtendedY(level, pos.getY())) return true;
        return EndlessVerticalEngine.world(level).hasPageSnapshot(new VerticalPagePos(
            pos.getX() >> 4, VerticalPageLayout.pageYForBlockY(pos.getY()), pos.getZ() >> 4));
    }
}
