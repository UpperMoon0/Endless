package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
    @Unique
    private static final long ENDLESS_EXTENDED_BLOCK_POS = Long.MIN_VALUE;

    @Inject(method = "readBlockPos", at = @At("HEAD"), cancellable = true)
    private void endless$readBlockPos(CallbackInfoReturnable<BlockPos> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        long packed = self.readLong();
        if (packed == ENDLESS_EXTENDED_BLOCK_POS && EndlessLogicalHeights.isActive()) {
            cir.setReturnValue(new BlockPos(self.readInt(), self.readInt(), self.readInt()));
        } else {
            cir.setReturnValue(BlockPos.of(packed));
        }
    }

    @Inject(method = "writeBlockPos", at = @At("HEAD"), cancellable = true)
    private void endless$writeBlockPos(BlockPos pos, CallbackInfoReturnable<FriendlyByteBuf> cir) {
        FriendlyByteBuf self = (FriendlyByteBuf) (Object) this;
        long packed = pos.asLong();
        if (EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())
            || (EndlessLogicalHeights.isActive() && packed == ENDLESS_EXTENDED_BLOCK_POS)) {
            self.writeLong(ENDLESS_EXTENDED_BLOCK_POS);
            self.writeInt(pos.getX());
            self.writeInt(pos.getY());
            self.writeInt(pos.getZ());
        } else {
            self.writeLong(packed);
        }
        cir.setReturnValue(self);
    }
}
