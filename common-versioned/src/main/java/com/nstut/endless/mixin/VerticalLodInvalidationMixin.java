package com.nstut.endless.mixin;

import com.nstut.endless.vertical.lod.VerticalLodClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class VerticalLodInvalidationMixin {
    @Inject(method="setLevel",at=@At("HEAD"))
    private void endless$clearLod(CallbackInfo ci) { VerticalLodClient.clear(); }
}
