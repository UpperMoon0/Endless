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
    @org.spongepowered.asm.mixin.injection.Inject(method="setupFog",at=@At("RETURN"))
    private static void endless$captureVerticalFog(net.minecraft.client.Camera camera,FogRenderer.FogMode mode,
        float distance,boolean thickFog,float partialTick,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if(mode!=FogRenderer.FogMode.FOG_TERRAIN || !com.nstut.endless.vertical.lod.VerticalLodFogUniform.enabled()) return;
        float[] color=com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();
        float start=com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(),end=com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        boolean environmental=com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape()==com.mojang.blaze3d.shaders.FogShape.SPHERE;
        com.nstut.endless.vertical.lod.VerticalLodClient.setFog(environmental?1_000_000:start,environmental?2_000_000:end,
            environmental?start:1_000_000,environmental?end:2_000_000,color[0],color[1],color[2],color[3],false);
    }
    @Redirect(method="setupColor",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I"))
    private static int endless$logicalFogFloor(ClientLevel level) {
        return LogicalFogFloor.select(EndlessLogicalHeights.isActive(),
            EndlessHeights.getMinBuildHeight(),level.getMinBuildHeight());
    }
}
