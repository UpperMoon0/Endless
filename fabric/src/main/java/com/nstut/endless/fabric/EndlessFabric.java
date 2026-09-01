package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Common/server Fabric bootstrap. Contains no client-only class references. */
public final class EndlessFabric implements ModInitializer {
    public static final ResourceLocation HEIGHT_SYNC_CHANNEL =
        new ResourceLocation(Endless.MOD_ID, "height_sync");
    public static final ResourceLocation VERTICAL_PAGE_CHANNEL =
        new ResourceLocation(Endless.MOD_ID, "vertical_page");

    private final Map<ServerLoginPacketListenerImpl, AckState> pending = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        Endless.init();

        VerticalNetworkBridge.registerSender((player, snapshot) -> {
            FriendlyByteBuf packet = PacketByteBufs.create();
            snapshot.write(packet);
            ServerPlayNetworking.send(player, VERTICAL_PAGE_CHANNEL, packet);
        });

        ServerLoginNetworking.registerGlobalReceiver(HEIGHT_SYNC_CHANNEL,
            (srv, listener, understood, buf, sync, sender) -> {
                AckState state = pending.remove(listener);
                if (state == null) return;
                if (!understood) {
                    listener.disconnect(Component.literal(
                        "This server requires Endless v0.5+ for sparse infinite-height worlds."));
                }
                state.ack.complete(understood);
            });

        ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, sync) -> {
            if (!EndlessLogicalHeights.isActive()) return;
            int min = EndlessHeights.getMinBuildHeight();
            int max = EndlessHeights.getMaxBuildHeight();
            AckState state = new AckState(min, max, new CompletableFuture<>());
            pending.put(listener, state);

            FriendlyByteBuf query = new FriendlyByteBuf(Unpooled.buffer());
            query.writeVarInt(min);
            query.writeVarInt(max);
            query.writeVarInt(EndlessLogicalHeights.MIN_BUILD_HEIGHT);
            query.writeVarInt(EndlessLogicalHeights.MAX_BUILD_HEIGHT);
            try {
                sender.sendPacket(HEIGHT_SYNC_CHANNEL, query);
            } catch (RuntimeException e) {
                pending.remove(listener);
                listener.disconnect(Component.literal(
                    "This server requires Endless v0.5+ for sparse infinite-height worlds."));
                state.ack.complete(false);
            }
            sync.waitFor(state.ack);
        });

        ServerLoginConnectionEvents.DISCONNECT.register((listener, server) -> {
            AckState state = pending.remove(listener);
            if (state != null) state.ack.complete(false);
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            EndlessHeights.loadPersistedRange(server);
            EndlessLogicalHeights.activate();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(EndlessHeights::syncWorldData);
        ServerTickEvents.END_SERVER_TICK.register(VerticalNetworkBridge::tickServer);
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
            EndlessVerticalEngine.unloadColumn(level, chunk.getPos().x, chunk.getPos().z));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            VerticalNetworkBridge.shutdown();
            EndlessLogicalHeights.deactivate();
        });
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
