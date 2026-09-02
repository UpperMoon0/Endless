package com.nstut.endless.forge;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Login-phase sync of the user logical range plus the separately bounded dense core. */
public class SyncHeightPacket implements IntSupplier {
    private int logicalMinBuildHeight;
    private int logicalMaxBuildHeight;
    private int denseMinBuildHeight;
    private int denseMaxBuildHeight;
    private int envelopeMinBuildHeight;
    private int envelopeMaxBuildHeight;
    private int loginIndex;

    public SyncHeightPacket() {
        this(
            EndlessHeights.getMinBuildHeight(),
            EndlessHeights.getMaxBuildHeight(),
            EndlessHeights.getDenseMinBuildHeight(),
            EndlessHeights.getDenseMaxBuildHeight(),
            EndlessLogicalHeights.MIN_BUILD_HEIGHT,
            EndlessLogicalHeights.MAX_BUILD_HEIGHT);
    }

    public SyncHeightPacket(
        int logicalMinBuildHeight,
        int logicalMaxBuildHeight,
        int denseMinBuildHeight,
        int denseMaxBuildHeight,
        int envelopeMinBuildHeight,
        int envelopeMaxBuildHeight
    ) {
        this.logicalMinBuildHeight = logicalMinBuildHeight;
        this.logicalMaxBuildHeight = logicalMaxBuildHeight;
        this.denseMinBuildHeight = denseMinBuildHeight;
        this.denseMaxBuildHeight = denseMaxBuildHeight;
        this.envelopeMinBuildHeight = envelopeMinBuildHeight;
        this.envelopeMaxBuildHeight = envelopeMaxBuildHeight;
    }

    public static void encode(SyncHeightPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.logicalMinBuildHeight);
        buf.writeVarInt(msg.logicalMaxBuildHeight);
        buf.writeVarInt(msg.denseMinBuildHeight);
        buf.writeVarInt(msg.denseMaxBuildHeight);
        buf.writeVarInt(msg.envelopeMinBuildHeight);
        buf.writeVarInt(msg.envelopeMaxBuildHeight);
    }

    public static SyncHeightPacket decode(FriendlyByteBuf buf) {
        return new SyncHeightPacket(
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt());
    }

    public static void handle(SyncHeightPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.setPacketHandled(true);
        if (msg.envelopeMinBuildHeight != EndlessLogicalHeights.MIN_BUILD_HEIGHT
            || msg.envelopeMaxBuildHeight != EndlessLogicalHeights.MAX_BUILD_HEIGHT) {
            throw new IllegalStateException("Server uses an incompatible Endless logical-height protocol");
        }
        EndlessHeights.applyEffective(
            msg.logicalMinBuildHeight,
            msg.logicalMaxBuildHeight,
            msg.denseMinBuildHeight,
            msg.denseMaxBuildHeight);
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
