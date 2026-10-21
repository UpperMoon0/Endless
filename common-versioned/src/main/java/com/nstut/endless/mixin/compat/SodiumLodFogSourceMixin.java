package com.nstut.endless.mixin.compat;

import com.nstut.endless.vertical.lod.VerticalLodFog;
import com.nstut.endless.vertical.lod.VerticalLodFogUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser",remap=false)
public abstract class SodiumLodFogSourceMixin {
    @ModifyVariable(method={
        "parseShader(Ljava/lang/String;)Ljava/util/List;",
        "parseShader(Ljava/lang/String;Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderConstants;)Ljava/lang/String;"},at=@At("HEAD"),argsOnly=true,ordinal=0)
    private static String endless$horizontalDistanceFog(String source) { return endless$adapt(source); }

    @org.spongepowered.asm.mixin.Unique
    private static String endless$adapt(String source) {
        VerticalLodFogUniform.clear();
        String adapted=VerticalLodFog.adapt(source);
        if(Boolean.getBoolean("endless.lodMetrics") && !adapted.equals(source))
            System.out.println("ENDLESS_LOD_FOG_SOURCE sodium adapted");
        return adapted;
    }
}
