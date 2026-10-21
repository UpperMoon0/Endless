package com.nstut.endless.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nstut.endless.vertical.lod.VerticalLodClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class VerticalLodRenderMixin {
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
        var pos=camera.getPosition();
        var target=Minecraft.getInstance().getMainRenderTarget();
        VerticalLodClient.setTarget(target.getColorTextureId(),target.getDepthTextureId(),target.width,target.height);
        var matrix=new Matrix4f(RenderSystem.getProjectionMatrix())
            .rotate(camera.rotation().conjugate(new Quaternionf()));
        VerticalLodClient.render(matrix,pos.x,pos.y,pos.z);
        return phase;
    }
}
