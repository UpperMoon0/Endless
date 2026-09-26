package com.nstut.endless.mixin;

import com.nstut.endless.network.ExtendedBlockPosCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends vanilla BlockPos packet encoding without changing a single byte for
 * normal positions. Long.MIN_VALUE decodes to an X position outside Minecraft's
 * legal world border, so Endless reserves it as an extended-position marker.
 */
@Mixin(FriendlyByteBuf.class)
public abstract class FriendlyByteBufMixin {
    @Inject(method = "readBlockPos()Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private void endless$readBlockPos(CallbackInfoReturnable<BlockPos> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        cir.setReturnValue(ExtendedBlockPosCodec.read(self));
    }

    @Inject(method = "readBlockPos(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private static void endless$readBlockPosStatic(ByteBuf buf, CallbackInfoReturnable<BlockPos> cir) {
        cir.setReturnValue(ExtendedBlockPosCodec.read(buf));
    }

    @Inject(method = "writeBlockPos(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/network/FriendlyByteBuf;", at = @At("HEAD"), cancellable = true)
    private void endless$writeBlockPos(BlockPos pos, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        cir.setReturnValue(ExtendedBlockPosCodec.write(self, pos));
    }

    @Inject(method = "writeBlockPos(Lio/netty/buffer/ByteBuf;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private static void endless$writeBlockPosStatic(ByteBuf buf, BlockPos pos, CallbackInfo ci) {
        ExtendedBlockPosCodec.write(buf, pos);
        ci.cancel();
    }
}
