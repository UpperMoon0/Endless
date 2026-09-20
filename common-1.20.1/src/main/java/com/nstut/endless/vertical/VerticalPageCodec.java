package com.nstut.endless.vertical;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Binary codec shared by disk persistence and page networking. */
public final class VerticalPageCodec {
    private VerticalPageCodec() {
    }

    public static byte[] encodeSection(LevelChunkSection section) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(section.getSerializedSize()));
        try {
            section.write(buf);
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(0, data);
            return data;
        } finally {
            buf.release();
        }
    }

    public static LevelChunkSection decodeSection(Level level, byte[] data) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        LevelChunkSection section = new LevelChunkSection(biomes);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            section.read(buf);
            if (buf.isReadable()) {
                throw new IllegalArgumentException("Trailing bytes in Endless vertical section payload: " + buf.readableBytes());
            }
            return section;
        } finally {
            buf.release();
        }
    }
}
