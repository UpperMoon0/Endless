package com.nstut.endless.mixin.compat;

import com.nstut.endless.config.EndlessConfig;
import com.nstut.endless.vertical.ShaderVerticalBlend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Optional Iris/Oculus hook; shader sources are expanded before preprocessing. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor", remap = false)
public abstract class ShaderPreprocessorMixin {
    @ModifyVariable(method = "glslPreprocessSource", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static String endless$horizontalNativeDhBlend(String source) {
        return ShaderVerticalBlend.adapt(source,
            EndlessConfig.getInstance().getRendering().getVerticalRenderSections());
    }
}
