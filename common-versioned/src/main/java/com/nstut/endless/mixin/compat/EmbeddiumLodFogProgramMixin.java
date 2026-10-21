package com.nstut.endless.mixin.compat;

import com.nstut.endless.vertical.lod.VerticalLodFogUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="org.embeddedt.embeddium.impl.gl.shader.GlProgram",remap=false)
public abstract class EmbeddiumLodFogProgramMixin {
    @Inject(method="bind",at=@At("TAIL"))
    private void endless$updateFog(CallbackInfo ci) { VerticalLodFogUniform.updateBoundProgram(); }
    @Inject(method="delete",at=@At("HEAD"))
    private void endless$forgetFog(CallbackInfo ci) { VerticalLodFogUniform.clear(); }
}
