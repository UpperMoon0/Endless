package com.nstut.endless.mixin;

import com.nstut.endless.testing.LiveCreateSoundTest;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe real sound-manager submissions only while the live regression is active. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void endless$observeNativeSound(SoundInstance sound, CallbackInfo ci) {
        LiveCreateSoundTest.observe(sound);
    }
}
