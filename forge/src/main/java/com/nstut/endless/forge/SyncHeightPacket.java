package com.nstut.endless.forge;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Login-phase sync of the bounded core plus the v0.5 logical sparse range. */
public class SyncHeightPacket implements IntSupplier {
    private int minBuildHeight;
    private int maxBuildHeight;
    private int logicalMinBuildHeight;
    private int logicalMaxBuildHeight;
    private int loginIndex;

    public SyncHeightPacket() {
        this(
            EndlessHeights.getMinBuildHeight(),
            EndlessHeights.getMaxBuildHeight(),
            EndlessLogicalHeights.MIN_BUILD_HEIGHT,
            EndlessLogicalHeights.MAX_BUILD_HEIGHT);
    }

    public SyncHeightPacket(
        int minBuildHeight,
        int maxBuildHeight,
        int logicalMinBuildHeight,
        int logicalMaxBuildHeight
    ) {
        this.minBuildHeight = minBuildHeight;
        this.maxBuildHeight = maxBuildHeight;
        this.logicalMinBuildHeight = logicalMinBuildHeight;
        this.logicalMaxBuildHeight = logicalMaxBuildHeight;
    }

    public static void encode(SyncHeightPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.minBuildHeight);
        buf.writeVarInt(msg.maxBuildHeight);
        buf.writeVarInt(msg.logicalMinBuildHeight);
        buf.writeVarInt(msg.logicalMaxBuildHeight);
    }

    public static SyncHeightPacket decode(FriendlyByteBuf buf) {
        return new SyncHeightPacket(
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt());
    }

    public static void handle(SyncHeightPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (msg.logicalMinBuildHeight != EndlessLogicalHeights.MIN_BUILD_HEIGHT
            || msg.logicalMaxBuildHeight != EndlessLogicalHeights.MAX_BUILD_HEIGHT) {
            throw new IllegalStateException("Server uses an incompatible Endless logical-height protocol");
        }
        EndlessHeights.applyEffective(msg.minBuildHeight, msg.maxBuildHeight);
        EndlessLogicalHeights.activate();
    }

    public int getLoginIndex() {
        return loginIndex;
    }

    public void setLoginIndex(int loginIndex) {
        this.loginIndex = loginIndex;
    }

    @Override
    public int getAsInt() {
        return loginIndex;
    }
}
