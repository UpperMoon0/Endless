package com.nstut.endless.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.nstut.endless.vertical.lod.VerticalLodFogUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=GlStateManager.class,remap=false)
public abstract class VerticalLodFogProgramMixin {
    @Inject(method="_glUseProgram",at=@At("TAIL"))
    private static void endless$updateFog(int program,CallbackInfo ci) { VerticalLodFogUniform.update(program); }
    @Inject(method={"glDeleteProgram","glLinkProgram"},at=@At("HEAD"))
    private static void endless$forgetFog(int program,CallbackInfo ci) { VerticalLodFogUniform.forget(program); }
}
