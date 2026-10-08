package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.LogicalFogFloor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The dense array floor is not the void boundary of a sparse world. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Redirect(method="setupColor",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I"))
    private static int endless$logicalFogFloor(ClientLevel level) {
        return LogicalFogFloor.select(EndlessLogicalHeights.isActive(),
            EndlessHeights.getMinBuildHeight(),level.getMinBuildHeight());
    }
}
