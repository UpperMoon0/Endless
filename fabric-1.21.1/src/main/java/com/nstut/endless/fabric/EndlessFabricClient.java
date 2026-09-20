package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.testing.LiveSameJvmRejoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client-only Fabric 1.21.1 bootstrap. */
public final class EndlessFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Endless.clientInit();
        LiveJoinTest.preseedStaleRangeIfRequested();

        ClientConfigurationNetworking.registerGlobalReceiver(
            EndlessFabric.HeightSyncPayload.TYPE,
            (payload, context) -> context.client().execute(() -> applyHeight(payload)));

        ClientPlayNetworking.registerGlobalReceiver(
            EndlessFabric.VerticalPagePayload.TYPE,
            (payload, context) -> context.client().execute(() -> {
                if (context.client().level == null || !EndlessLogicalHeights.isActive()) return;
                com.nstut.endless.vertical.VerticalClientUpdates.apply(context.client(), payload.snapshot());
            }));

        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
            EndlessVerticalEngine.unloadColumn(level, chunk.getPos().x, chunk.getPos().z));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client.level));

        if (LiveJoinTest.isArmed()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> LiveJoinTest.tick());
        }
        if (LiveSameJvmRejoinTest.isArmed()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> LiveSameJvmRejoinTest.tick());
        }
    }

    private static void applyHeight(EndlessFabric.HeightSyncPayload payload) {
        if (payload.envelopeMin() != EndlessLogicalHeights.MIN_BUILD_HEIGHT
            || payload.envelopeMax() != EndlessLogicalHeights.MAX_BUILD_HEIGHT) {
            throw new IllegalStateException("Server uses an incompatible Endless logical-height protocol");
        }
        EndlessHeights.applyEffective(
            payload.logicalMin(), payload.logicalMax(), payload.denseMin(), payload.denseMax());
        EndlessLogicalHeights.activate();
    }

    private static void reset(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level != null) EndlessVerticalEngine.close(level);
    }
}
