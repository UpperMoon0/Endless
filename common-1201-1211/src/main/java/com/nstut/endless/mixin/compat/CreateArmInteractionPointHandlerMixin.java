package com.nstut.endless.mixin.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointHandler", remap = false)
public abstract class CreateArmInteractionPointHandlerMixin {
    @Shadow(remap = false) private static long lastBlockPos;
    @Unique private static BlockPos endless$lastExactBlockPos;

    @Inject(method = "tick", at = @At("HEAD"), require = 1, remap = false)
    private static void endless$invalidatePackedAlias(CallbackInfo ci) {
        BlockPos hovered = endless$hovered();
        if (hovered != null && lastBlockPos != -1 && lastBlockPos == hovered.asLong()
            && endless$lastExactBlockPos != null && !endless$lastExactBlockPos.equals(hovered)) {
            lastBlockPos = -1;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 1, remap = false)
    private static void endless$rememberExactHover(CallbackInfo ci) {
        BlockPos hovered = endless$hovered();
        if (hovered != null && lastBlockPos != -1 && lastBlockPos == hovered.asLong())
            endless$lastExactBlockPos = hovered.immutable();
        else if (lastBlockPos == -1)
            endless$lastExactBlockPos = null;
    }

    @Unique
    private static BlockPos endless$hovered() {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit instanceof BlockHitResult blockHit ? blockHit.getBlockPos() : null;
    }
}
