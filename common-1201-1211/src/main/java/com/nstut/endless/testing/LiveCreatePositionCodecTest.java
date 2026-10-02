package com.nstut.endless.testing;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Native Create methods after actual loader mixin transformation, not surrogate codecs. */
public final class LiveCreatePositionCodecTest {
    public static final String PASS = "ENDLESS_CREATE_POSITION_CODECS_PASS";
    private LiveCreatePositionCodecTest() {}
    public static void run(ServerLevel level) throws ReflectiveOperationException {
        Class<?> nodeType = Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation");
        Class<?> paletteType = Class.forName("com.simibubi.create.content.trains.graph.DimensionPalette");
        Object palette = paletteType.getConstructor().newInstance();
        Method send = nodeType.getMethod("send", FriendlyByteBuf.class, paletteType);
        Method receive = nodeType.getMethod("receive", FriendlyByteBuf.class, paletteType);
        int[] heights = {0, 320, -16385, -16384, -16383, 16383, 16384, -1_000_000, 1_000_000, -8_000_000, 8_000_000};
        for (int y : heights) for (int offset : new int[]{0, 1, 15}) {
            Object node = nodeType.getConstructor(double.class, double.class, double.class).newInstance(-12.5, (double) y, 13.5);
            nodeType.getMethod("in", net.minecraft.resources.ResourceKey.class).invoke(node, offset == 1 ? Level.NETHER : Level.OVERWORLD);
            nodeType.getField("yOffsetPixels").setInt(node, offset);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                send.invoke(node, buffer, palette);
                // Unextended ordinary nodes must retain Create's exact native bytes.
                if (y > -16384 && y <= 16383) {
                    FriendlyByteBuf nativeBytes = new FriendlyByteBuf(Unpooled.buffer());
                    try {
                        nativeBytes.writeVarInt(-25); nativeBytes.writeShort(y * 2); nativeBytes.writeVarInt(27);
                        nativeBytes.writeVarInt(offset);
                        nativeBytes.writeVarInt((int) paletteType.getMethod("encode", net.minecraft.resources.ResourceKey.class)
                            .invoke(palette, nodeType.getField("dimension").get(node)));
                        require(io.netty.buffer.ByteBufUtil.equals(buffer, nativeBytes), "ordinary train packet bytes changed");
                    } finally { nativeBytes.release(); }
                }
                buffer.writeInt(0x51A7C0DE);
                Object restored = receive.invoke(null, buffer, palette);
                require(node.equals(restored), "train node packet changed coordinates/dimension/pixel offset at Y=" + y);
                require(nodeType.getMethod("getLocation").invoke(node).equals(nodeType.getMethod("getLocation").invoke(restored)), "train world position shifted");
                require(buffer.readInt() == 0x51A7C0DE && !buffer.isReadable(), "train packet consumed the next node/payload");
                CompoundTag disk = (CompoundTag) nodeType.getMethod("write", paletteType).invoke(node, palette);
                require(node.equals(nodeType.getMethod("read", CompoundTag.class, paletteType).invoke(null, disk, palette)), "train disk position changed");
            } finally { buffer.release(); }
        }
        System.out.println("ENDLESS_CREATE_TRAIN_POSITION_PACKET_PASS native=true shortEdges=true sentinel=true millionAndEnvelope=true dimensions=true pixelOffsets=true framing=true");
        Class<?> errorType = Class.forName("com.simibubi.create.content.contraptions.AssemblyException");
        for (int y : new int[]{-8_000_000, -2049, -2048, 2047, 2048, 1_000_000, 8_000_000}) {
            BlockPos pos = new BlockPos(7, y, 9);
            Object error = errorType.getMethod("unloadedChunk", BlockPos.class).invoke(null, pos);
            CompoundTag saved = new CompoundTag();
            LiveCreateNbt.writeAssemblyException(level, saved, error);
            Object restored = LiveCreateNbt.readAssemblyException(level, saved);
            require(pos.equals(errorType.getMethod("getPosition").invoke(restored)), "assembly error highlight shifted Y=" + y);
            CompoundTag rewritten = new CompoundTag();
            LiveCreateNbt.writeAssemblyException(level, rewritten, restored);
            require(com.google.gson.JsonParser.parseString(saved.getCompound("LastException").getString("Component"))
                .equals(com.google.gson.JsonParser.parseString(rewritten.getCompound("LastException").getString("Component"))),
                "assembly error component JSON changed");
            saved.getCompound("LastException").remove("EndlessPosition");
            require(BlockPos.of(pos.asLong()).equals(errorType.getMethod("getPosition").invoke(LiveCreateNbt.readAssemblyException(level, saved))), "legacy assembly exception reading changed");
        }
        require(LiveCreateNbt.readAssemblyException(level, new CompoundTag()) == null, "missing exception must stay null");
        System.out.println(PASS + " nativeTrainPacket=true shortEdges=true millionAndEnvelope=true pixelOffsets=true dimensions=true framing=true assemblyErrorDisk=true legacy=true");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
