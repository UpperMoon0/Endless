package com.nstut.endless.mixin;

import com.nstut.endless.vertical.lod.VerticalLodClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class VerticalLodRenderMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.state.GameRenderState gameRenderState;
    @org.spongepowered.asm.mixin.Unique
    private final Matrix4f endless$worldProjection = new Matrix4f();

    @ModifyArg(method="renderLevel",at=@At(value="INVOKE",
        target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",ordinal=0),index=0)
    private org.joml.Matrix4fc endless$captureWorldProjection(org.joml.Matrix4fc projection) {
        endless$worldProjection.set(projection);
        return projection;
    }

    // This phase follows the complete native/Iris/Oculus level pass and precedes
    // the hand projection/depth clear. It also runs when hand rendering is disabled.
    @ModifyArg(method="renderLevel",at=@At(value="INVOKE",
        target="Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"),index=0)
    private String endless$renderVerticalLods(String phase) {
        if(!phase.equals("hand")) return phase;
        if(!com.nstut.endless.vertical.lod.VerticalLodFogUniform.enabled()) return phase;
        var config=com.nstut.endless.config.EndlessConfig.getInstance().getRendering();
        if(config.getVerticalLodSections()<=config.getVerticalRenderSections()) return phase;
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera();
        var pos=camera.position();
        var target=Minecraft.getInstance().getMainRenderTarget();
        if(!(target.getColorTexture() instanceof com.mojang.blaze3d.opengl.GlTexture color)
            || !(target.getDepthTexture() instanceof com.mojang.blaze3d.opengl.GlTexture depth)) return phase;
        VerticalLodClient.setTarget(color.glId(),depth.glId(),target.width,target.height);
        var fog=gameRenderState.levelRenderState.cameraRenderState.fogData;
        VerticalLodClient.setFog(fog.renderDistanceStart,fog.renderDistanceEnd,fog.environmentalStart,fog.environmentalEnd,
            fog.color.x,fog.color.y,fog.color.z,fog.color.w,true);
        var matrix=new Matrix4f(endless$worldProjection).mul(gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix);
        VerticalLodClient.render(matrix,pos.x,pos.y,pos.z);
        return phase;
    }
}
