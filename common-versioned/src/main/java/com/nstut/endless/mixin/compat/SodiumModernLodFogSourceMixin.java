package com.nstut.endless.mixin.compat;

import com.nstut.endless.vertical.lod.VerticalLodFog;
import com.nstut.endless.vertical.lod.VerticalLodFogUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Selected by parser capabilities, so old development remappers cannot fuzzy-match it. */
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser",remap=false)
public abstract class SodiumModernLodFogSourceMixin {
    @ModifyVariable(method="parseShader(Ljava/lang/String;Ljava/lang/String;)V",at=@At("HEAD"),argsOnly=true,ordinal=1)
    private String endless$horizontalIncludeFog(String source) {
        VerticalLodFogUniform.clear();return VerticalLodFog.adapt(source);
    }
}
