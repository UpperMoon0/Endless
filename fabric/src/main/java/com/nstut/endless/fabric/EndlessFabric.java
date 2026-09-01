package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

/** Fabric-specific implementation of the Endless mod. */
public class EndlessFabric implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {
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
                if (state == null) {
                    return;
                }
                if (!understood) {
                    listener.disconnect(Component.literal(
                        "This server requires Endless v0.5+ for sparse infinite-height worlds."));
                }
                state.ack.complete(understood);
            });

        ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, sync) -> {
            if (!EndlessLogicalHeights.isActive()) {
                return;
            }

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
            if (state != null) {
                state.ack.complete(false);
            }
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            EndlessHeights.loadPersistedRange(server);
            EndlessLogicalHeights.activate();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(EndlessHeights::syncWorldData);
        ServerTickEvents.END_SERVER_TICK.register(VerticalNetworkBridge::tickServer);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            VerticalNetworkBridge.shutdown();
            EndlessLogicalHeights.deactivate();
        });
    }

    @Override
    public void onInitializeClient() {
        Endless.clientInit();
        LiveJoinTest.preseedStaleRangeIfRequested();

        ClientLoginNetworking.registerGlobalReceiver(HEIGHT_SYNC_CHANNEL,
            (client, handler, buf, responseSink) -> {
                int min = buf.readVarInt();
                int max = buf.readVarInt();
                int logicalMin = buf.readVarInt();
                int logicalMax = buf.readVarInt();
                CompletableFuture<FriendlyByteBuf> result = new CompletableFuture<>();
                client.execute(() -> {
                    try {
                        if (logicalMin != EndlessLogicalHeights.MIN_BUILD_HEIGHT
                            || logicalMax != EndlessLogicalHeights.MAX_BUILD_HEIGHT) {
                            throw new IllegalStateException(
                                "Server uses an incompatible Endless logical-height protocol");
                        }
                        EndlessHeights.applyEffective(min, max);
                        EndlessLogicalHeights.activate();
                        result.complete(new FriendlyByteBuf(Unpooled.buffer()));
                    } catch (Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
                return result;
            });

        ClientPlayNetworking.registerGlobalReceiver(VERTICAL_PAGE_CHANNEL,
            (client, handler, buf, responseSender) -> {
                VerticalPageSnapshot snapshot = VerticalPageSnapshot.read(buf);
                client.execute(() -> {
                    if (client.level == null || !EndlessLogicalHeights.isActive()) {
                        return;
                    }
                    EndlessVerticalEngine.world(client.level).applySnapshot(snapshot);
                    client.levelRenderer.allChanged();
                });
            });

        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) -> {
            EndlessLogicalHeights.deactivate();
            EndlessHeights.resetToLocalConfig();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.level != null) {
                EndlessVerticalEngine.close(client.level);
            }
            EndlessLogicalHeights.deactivate();
            EndlessHeights.resetToLocalConfig();
        });

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
