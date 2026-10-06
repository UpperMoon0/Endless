package com.nstut.endless.fabric.mixin.compat.sodium;
import com.nstut.endless.fabric.compat.EmbeddiumFrameClock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void endless$frame(boolean render, CallbackInfo ci) { EmbeddiumFrameClock.beginFrame(); }
    @Inject(method = "runTick", at = @At("RETURN"))
    private void endless$afterFrame(boolean render, CallbackInfo ci) { EmbeddiumFrameClock.endFrame(); }
}
