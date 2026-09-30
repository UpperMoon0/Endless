package com.nstut.endless.mixin.compat;

import com.nstut.endless.compat.create.CreateKineticNetworkAccess;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Version-specific Create NBT signatures for unresolved legacy identities. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class CreateKineticNbtMixin {
    @Inject(method = "read", at = @At("HEAD"), require = 1, remap = false)
    private void endless$readPending(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ((CreateKineticNetworkAccess) this).endless$readLegacyMarker(tag);
    }

    @Inject(method = "write", at = @At("HEAD"), require = 1, remap = false)
    private void endless$writePending(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        ((CreateKineticNetworkAccess) this).endless$writeLegacyMarker(tag);
    }
}
