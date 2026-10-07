package com.nstut.endless.forge.mixin.compat.embeddium;

import com.nstut.endless.forge.compat.LoadedColumnBlockEntities;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class GlobalRendererReloadMixin {
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"), remap = true)
    private void endless$reclassifyGlobals(CallbackInfo ci) {
        var renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer != null) ((LoadedColumnBlockEntities) renderer).endless$invalidateGlobalRenderers();
    }
}
