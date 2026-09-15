package com.nstut.endless.mixin;

import com.nstut.endless.network.ExtendedBlockPosCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends vanilla BlockPos packet encoding without changing a single byte for
 * normal positions. Long.MIN_VALUE decodes to an X position outside Minecraft's
 * legal world border, so Endless reserves it as an extended-position marker.
 */
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufMixin {
    @Inject(method = "readBlockPos", at = @At("HEAD"), cancellable = true)
    private void endless$readBlockPos(CallbackInfoReturnable<BlockPos> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        cir.setReturnValue(ExtendedBlockPosCodec.read(self));
    }

    @Inject(method = "writeBlockPos", at = @At("HEAD"), cancellable = true)
    private void endless$writeBlockPos(BlockPos pos, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        cir.setReturnValue(ExtendedBlockPosCodec.write(self, pos));
    }
}
