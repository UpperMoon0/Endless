package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.network.EndlessNetworking;
import com.nstut.endless.testing.LiveJoinTest;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-specific implementation of the Endless mod.
 *
 * <p>Initial build-range synchronization happens during the login phase, not
 * the play phase. The play phase is too late: section payloads on the wire
 * carry no Y coordinates, so the client must already know the effective build
 * range before any chunk packet arrives. {@code ServerPlayNetworking} cannot
 * deliver a range to a client that has not yet declared the channel, which
 * is exactly the case for a brand-new connection. We therefore use
 * {@link ServerLoginNetworking} via {@link ServerLoginConnectionEvents}, which
 * fires after the client's login start and can hold the login pipeline open
 * via {@code LoginSynchronizer#waitFor} until the client has acknowledged the
 * authoritative range. A client whose response carries
 * {@code understood=false} (vanilla clients, or a modded client that does
 * not implement the Endless login channel) is rejected with a disconnect
 * during login when the server's range is extended beyond vanilla.</p>
 *
 * <p>The Fabric client applies the range inside the login query handler and
 * resets to the local file config on disconnect.</p>
 */
public class EndlessFabric implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {

    public static final ResourceLocation HEIGHT_SYNC_CHANNEL =
        new ResourceLocation(Endless.MOD_ID, "height_sync");

    private final Map<ServerLoginPacketListenerImpl, AckState> pending = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        Endless.init();

        EndlessNetworking.registerLoginSync(new EndlessNetworking.LoginSync() {});

        ServerLoginNetworking.registerGlobalReceiver(HEIGHT_SYNC_CHANNEL,
            (srv, listener, understood, buf, sync, sender) -> {
                AckState state = pending.remove(listener);
                if (state == null) {
                    return;
                }
                if (!understood) {
                    listener.disconnect(Component.literal(
                        "This server requires the Endless mod: its build range ["
                            + state.min + ", " + state.max
                            + ") is extended beyond vanilla."));
                }
                state.ack.complete(understood);
            });

        ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, sync) -> {
            int min = EndlessHeights.getMinBuildHeight();
            int max = EndlessHeights.getMaxBuildHeight();
            boolean vanillaRange = min == EndlessHeights.VANILLA_MIN_BUILD_HEIGHT
                && max == EndlessHeights.VANILLA_MAX_BUILD_HEIGHT;
            if (vanillaRange) {
                return;
            }

            AckState state = new AckState(min, max, new CompletableFuture<>());
            pending.put(listener, state);

            FriendlyByteBuf query = new FriendlyByteBuf(Unpooled.buffer());
            query.writeVarInt(min);
            query.writeVarInt(max);

            try {
                sender.sendPacket(HEIGHT_SYNC_CHANNEL, query);
            } catch (RuntimeException e) {
                pending.remove(listener);
                listener.disconnect(Component.literal(
                    "This server requires the Endless mod: its build range ["
                        + min + ", " + max + ") is extended beyond vanilla."));
                state.ack.complete(false);
            }

            sync.waitFor(state.ack);
        });

        ServerLoginConnectionEvents.DISCONNECT.register((listener, server) -> {
            AckState state = pending.remove(listener);
            if (state != null) {
                state.ack.complete(false);
            }
        });

        ServerLifecycleEvents.SERVER_STARTING.register(EndlessHeights::loadPersistedRange);
        ServerLifecycleEvents.SERVER_STARTED.register(EndlessHeights::syncWorldData);
    }

    @Override
    public void onInitializeClient() {
        Endless.clientInit();

        ClientLoginNetworking.registerGlobalReceiver(HEIGHT_SYNC_CHANNEL,
            (client, handler, buf, responseSink) -> {
                int min = buf.readVarInt();
                int max = buf.readVarInt();
                client.execute(() -> EndlessHeights.applyEffective(min, max));
                FriendlyByteBuf ack = new FriendlyByteBuf(Unpooled.buffer());
                return CompletableFuture.completedFuture(ack);
            });

        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) ->
            EndlessHeights.resetToLocalConfig());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            EndlessHeights.resetToLocalConfig());

        if (LiveJoinTest.isArmed()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> LiveJoinTest.tick());
        }
    }

    @Override
    public void onInitializeServer() {
        Endless.serverInit();
    }

    private static final class AckState {
        final int min;
        final int max;
        final CompletableFuture<Boolean> ack;

        AckState(int min, int max, CompletableFuture<Boolean> ack) {
            this.min = min;
            this.max = max;
            this.ack = ack;
        }
    }
}
