package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

import java.util.concurrent.CompletableFuture;

/** Client-only Fabric bootstrap. Never loaded on a dedicated server. */
public final class EndlessFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Endless.clientInit();
        LiveJoinTest.preseedStaleRangeIfRequested();

        ClientLoginNetworking.registerGlobalReceiver(EndlessFabric.HEIGHT_SYNC_CHANNEL,
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
                            throw new IllegalStateException("Incompatible Endless logical-height protocol");
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

        ClientPlayNetworking.registerGlobalReceiver(EndlessFabric.VERTICAL_PAGE_CHANNEL,
            (client, handler, buf, responseSender) -> {
                VerticalPageSnapshot snapshot = VerticalPageSnapshot.read(buf);
                client.execute(() -> {
                    if (client.level == null || !EndlessLogicalHeights.isActive()) return;
                    EndlessVerticalEngine.world(client.level).applySnapshot(snapshot);
                    client.levelRenderer.allChanged();
                });
            });

        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
            EndlessVerticalEngine.unloadColumn(level, chunk.getPos().x, chunk.getPos().z));
        ClientLoginConnectionEvents.DISCONNECT.register((handler, client) -> reset(client.level));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client.level));
        if (LiveJoinTest.isArmed()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> LiveJoinTest.tick());
        }
    }

    private static void reset(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level != null) EndlessVerticalEngine.close(level);
        EndlessLogicalHeights.deactivate();
        EndlessHeights.resetToLocalConfig();
    }
}
