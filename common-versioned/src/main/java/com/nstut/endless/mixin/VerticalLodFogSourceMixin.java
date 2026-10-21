package com.nstut.endless.mixin;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.nstut.endless.vertical.lod.VerticalLodFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GlslPreprocessor.class)
public abstract class VerticalLodFogSourceMixin {
    @ModifyVariable(method="processImports",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private String endless$horizontalDistanceFog(String source) { return VerticalLodFog.adapt(source); }
}
