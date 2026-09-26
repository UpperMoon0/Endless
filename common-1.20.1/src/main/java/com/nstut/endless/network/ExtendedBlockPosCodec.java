package com.nstut.endless.network;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Shared codec used by the FriendlyByteBuf mixin and its regression tests. */
public final class ExtendedBlockPosCodec {
    public static final long EXTENDED_MARKER = Long.MIN_VALUE;

    private ExtendedBlockPosCodec() {}

    public static BlockPos read(FriendlyByteBuf buf) {
        long packed = buf.readLong();
        if (packed == EXTENDED_MARKER && EndlessLogicalHeights.isActive()) {
            return new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        }
        return BlockPos.of(packed);
    }

    public static FriendlyByteBuf write(FriendlyByteBuf buf, BlockPos pos) {
        long packed = pos.asLong();
        if (EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())
            || (EndlessLogicalHeights.isActive() && packed == EXTENDED_MARKER)) {
            buf.writeLong(EXTENDED_MARKER);
            buf.writeInt(pos.getX());
            buf.writeInt(pos.getY());
            buf.writeInt(pos.getZ());
        } else {
            buf.writeLong(packed);
        }
        return buf;
    }
}
