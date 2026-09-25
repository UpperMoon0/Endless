package com.nstut.endless.network;

import com.nstut.endless.heights.EndlessLogicalHeights;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Shared codec used by the FriendlyByteBuf mixin and its regression tests. */
public final class ExtendedBlockPosCodec {
    public static final long EXTENDED_MARKER = Long.MIN_VALUE;

    private ExtendedBlockPosCodec() {}

    public static BlockPos read(ByteBuf buf) {
        long packed = buf.readLong();
        if (packed == EXTENDED_MARKER && EndlessLogicalHeights.isActive()) {
            return new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        }
        return BlockPos.of(packed);
    }

    public static FriendlyByteBuf write(FriendlyByteBuf buf, BlockPos pos) {
        write((ByteBuf) buf, pos);
        return buf;
    }

    /** BlockPos.STREAM_CODEC uses FriendlyByteBuf's static raw-ByteBuf helpers on 1.21+. */
    public static void write(ByteBuf buf, BlockPos pos) {
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
    }
}
