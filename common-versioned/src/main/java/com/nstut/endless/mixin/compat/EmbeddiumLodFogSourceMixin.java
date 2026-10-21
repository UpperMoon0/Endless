package com.nstut.endless.mixin.compat;

import com.nstut.endless.vertical.lod.VerticalLodFog;
import com.nstut.endless.vertical.lod.VerticalLodFogUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets="org.embeddedt.embeddium.impl.gl.shader.ShaderParser",remap=false)
public abstract class EmbeddiumLodFogSourceMixin {
    @ModifyVariable(method={"parseShader(Ljava/lang/String;)Ljava/util/List;", "parseShader(Ljava/lang/String;Lorg/embeddedt/embeddium/impl/gl/shader/ShaderConstants;)Ljava/lang/String;"},at=@At("HEAD"),argsOnly=true,ordinal=0)
    private static String endless$horizontalDistanceFog(String source) {
        VerticalLodFogUniform.clear();
        String adapted=VerticalLodFog.adapt(source);
        if(Boolean.getBoolean("endless.lodMetrics") && !adapted.equals(source))
            System.out.println("ENDLESS_LOD_FOG_SOURCE sodium adapted");
        return adapted;
    }
}
