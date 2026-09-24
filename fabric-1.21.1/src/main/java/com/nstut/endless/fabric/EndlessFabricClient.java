package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.testing.LiveSameJvmRejoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalClientUpdates;
import com.nstut.endless.vertical.VerticalPageLayout;
import net.minecraft.client.Minecraft;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client-only Fabric 1.21.1 bootstrap. */
public final class EndlessFabricClient implements ClientModInitializer {
    private static final int WINDOW_REFRESH_ATTEMPTS = 3;
    private static final int WINDOW_REFRESH_RETRY_INTERVAL_TICKS = 4;

    private static Object lastWindowLevel;
    private static int lastWindowPageY = Integer.MIN_VALUE;
    private static int windowRefreshAttempts;
    private static int windowRefreshCooldown;

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

        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            Minecraft client = Minecraft.getInstance();
            boolean currentPlayerChunk = client.level == level
                && client.player != null
                && client.player.chunkPosition().equals(chunk.getPos());

            // A vertical-only teleport can make Fabric recycle the current
            // horizontal LevelChunk. Dropping the sparse column here loses the
            // freshly synchronized page even though X/Z never left the client's
            // view. Real horizontal unloads still evict normally, and disconnect
            // closes the complete client vertical world below.
            if (!currentPlayerChunk) {
                EndlessVerticalEngine.unloadColumn(level, chunk.getPos().x, chunk.getPos().z);
            }
            rearmVerticalWindowAfterCenterChunkChange(level, chunk.getPos());
        });
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
            rearmVerticalWindowAfterCenterChunkChange(level, chunk.getPos()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client.level));
        ClientTickEvents.END_CLIENT_TICK.register(EndlessFabricClient::refreshVerticalWindowIfNeeded);
        ClientTickEvents.END_CLIENT_TICK.register(VerticalClientUpdates::tick);

        if (LiveJoinTest.isArmed()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> LiveJoinTest.tick());
        }
        if (LiveSameJvmRejoinTest.isArmed()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> LiveSameJvmRejoinTest.tick());
        }
    }

    private static void refreshVerticalWindowIfNeeded(Minecraft client) {
        if (client.level == null || client.player == null || !EndlessLogicalHeights.isActive()) {
            resetWindowRefresh();
            return;
        }

        int pageY = VerticalPageLayout.pageYForBlockY(client.player.getBlockY());
        if (client.level != lastWindowLevel || pageY != lastWindowPageY) {
            lastWindowLevel = client.level;
            lastWindowPageY = pageY;
            windowRefreshAttempts = 0;
            windowRefreshCooldown = 0;
        }

        if (windowRefreshAttempts >= WINDOW_REFRESH_ATTEMPTS) {
            return;
        }
        if (windowRefreshCooldown > 0) {
            windowRefreshCooldown--;
            return;
        }
        if (!ClientPlayNetworking.canSend(EndlessFabric.VerticalWindowRefreshPayload.TYPE)) {
            return;
        }

        // A server-driven vertical teleport and its first sparse-window payload
        // can cross the client's own teleport/view-window work in the same tick.
        // Re-request the authoritative page window a few times after the page
        // transition instead of treating one timing-sensitive request as an ACK.
        ClientPlayNetworking.send(new EndlessFabric.VerticalWindowRefreshPayload());
        windowRefreshAttempts++;
        windowRefreshCooldown = WINDOW_REFRESH_RETRY_INTERVAL_TICKS;
    }

    private static void rearmVerticalWindowAfterCenterChunkChange(
        net.minecraft.client.multiplayer.ClientLevel level,
        net.minecraft.world.level.ChunkPos chunkPos
    ) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != level || client.player == null || !EndlessLogicalHeights.isActive()) {
            return;
        }
        if (!client.player.chunkPosition().equals(chunkPos)) {
            return;
        }

        // Fabric can recycle the current LevelChunk after a server-driven
        // vertical teleport, so re-arm the authoritative window request after
        // either half of that lifecycle instead of relying only on page timing.
        lastWindowLevel = level;
        lastWindowPageY = VerticalPageLayout.pageYForBlockY(client.player.getBlockY());
        windowRefreshAttempts = 0;
        windowRefreshCooldown = WINDOW_REFRESH_RETRY_INTERVAL_TICKS;
    }

    private static void resetWindowRefresh() {
        lastWindowLevel = null;
        lastWindowPageY = Integer.MIN_VALUE;
        windowRefreshAttempts = 0;
        windowRefreshCooldown = 0;
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
        VerticalClientUpdates.reset();
        resetWindowRefresh();
    }
}
