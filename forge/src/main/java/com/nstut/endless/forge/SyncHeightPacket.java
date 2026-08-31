package com.nstut.endless.forge;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server-to-client sync of the authoritative build range, sent before any
 * chunk packet so the client builds its section arrays with the server's
 * layout.
 */
public class SyncHeightPacket {

    private final int minBuildHeight;
    private final int maxBuildHeight;

    public SyncHeightPacket(int minBuildHeight, int maxBuildHeight) {
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
    }

    public static void encode(SyncHeightPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.minBuildHeight);
        buf.writeVarInt(msg.maxBuildHeight);
    }

    public static SyncHeightPacket decode(FriendlyByteBuf buf) {
        return new SyncHeightPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SyncHeightPacket msg, Supplier<NetworkEvent.Context> ctx) {
        // The message is registered strictly PLAY_TO_CLIENT; validate anyway so
        // a spoofed C2S packet can never modify the server's effective range.
        if (ctx.get().getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            return;
        }
        ctx.get().enqueueWork(() -> EndlessHeights.applyEffective(msg.minBuildHeight, msg.maxBuildHeight));
        ctx.get().setPacketHandled(true);
    }
}
