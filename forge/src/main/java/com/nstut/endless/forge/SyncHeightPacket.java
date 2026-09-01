package com.nstut.endless.forge;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Server-to-client login-phase sync of the authoritative build range.
 *
 * <p>Registered as a Forge login packet via {@code markAsLoginPacket()}: the
 * message is gathered once per connection during the FML handshake
 * (NEGOTIATING state) and reaches the client before the login advances, so it
 * is processed before {@code ClientboundLoginPacket} constructs the
 * ClientLevel. The no-arg constructor is invoked at gather time on the
 * server, after the world's persisted range has been loaded.</p>
 *
 * <p>The packet is fire-and-forget ({@code noResponse()}): Forge's
 * needsResponse tracking can only be drained by FML's own handshake channel,
 * and ordering is already guaranteed because packets on one TCP connection
 * are processed in FIFO order on the client's event loop — the client applies
 * the range inline while handling this packet, strictly before it processes
 * the login packet that creates the world.</p>
 */
public class SyncHeightPacket implements IntSupplier {

    private int minBuildHeight;
    private int maxBuildHeight;
    private int loginIndex;

    /**
     * Login packets are instantiated reflectively at gather time, once per
     * connection, after the effective range has been loaded.
     */
    public SyncHeightPacket() {
        this(EndlessHeights.getMinBuildHeight(), EndlessHeights.getMaxBuildHeight());
    }

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
        // Registered strictly LOGIN_TO_CLIENT; a spoofed C2S packet fails the
        // direction check in IndexedMessageCodec and can never modify the
        // client's effective range through this handler.
        ctx.get().setPacketHandled(true);
        EndlessHeights.applyEffective(msg.minBuildHeight, msg.maxBuildHeight);
    }

    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    public int getMaxBuildHeight() {
        return maxBuildHeight;
    }

    public int getLoginIndex() {
        return loginIndex;
    }

    public void setLoginIndex(int loginIndex) {
        this.loginIndex = loginIndex;
    }

    @Override
    public int getAsInt() {
        return getLoginIndex();
    }
}
