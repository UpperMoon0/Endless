package com.nstut.endless.network;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtendedBlockPosCodecTest {
    @BeforeEach
    void activateExtendedRange() {
        EndlessHeights.applyEffective(-4096, 4096, -64, 320);
        EndlessLogicalHeights.activate();
    }

    @AfterEach
    void restoreDefaults() {
        EndlessLogicalHeights.deactivate();
        EndlessHeights.resetToLocalConfig();
    }

    @Test
    void extendedYRoundTripsAcrossPackedBoundaries() {
        for (int y : new int[]{-4096, -2049, 2048, 4095}) {
            BlockPos pos = new BlockPos(12345, y, -23456);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ExtendedBlockPosCodec.write(buf, pos);
            assertEquals(20, buf.readableBytes(), "extended encoding must be marker + xyz ints at y=" + y);
            assertEquals(ExtendedBlockPosCodec.EXTENDED_MARKER, buf.getLong(0));
            assertEquals(pos, ExtendedBlockPosCodec.read(buf));
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void packedBoundaryCanariesRemainVanillaCompatible() {
        for (int y : new int[]{-2048, 2047, -64, 0, 319}) {
            BlockPos pos = new BlockPos(31, y, -17);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ExtendedBlockPosCodec.write(buf, pos);
            assertEquals(8, buf.readableBytes(), "packed-range position must keep vanilla wire width at y=" + y);
            assertEquals(pos.asLong(), buf.getLong(0), "packed-range position must keep vanilla packed long bytes");
            assertEquals(pos, ExtendedBlockPosCodec.read(buf));
            assertEquals(0, buf.readableBytes());
        }
    }
}
