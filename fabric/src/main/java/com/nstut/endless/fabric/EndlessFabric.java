package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.network.EndlessNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric-specific implementation of the Endless mod.
 */
public class EndlessFabric implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {

    public static final ResourceLocation HEIGHT_SYNC_CHANNEL =
        new ResourceLocation(Endless.MOD_ID, "height_sync");

    @Override
    public void onInitialize() {
        // Common initialization code
        Endless.init();

        EndlessNetworking.registerSender(new EndlessNetworking.Sender() {
            @Override
            public boolean canSend(ServerPlayer player) {
                return ServerPlayNetworking.canSend(player, HEIGHT_SYNC_CHANNEL);
            }

            @Override
            public void send(ServerPlayer player, int min, int max) {
                FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                buf.writeVarInt(min);
                buf.writeVarInt(max);
                ServerPlayNetworking.send(player, HEIGHT_SYNC_CHANNEL, buf);
            }
        });

        // Read the world's persisted range before any level exists so chunk
        // deserialization uses it, then mirror it into SavedData once loaded.
        ServerLifecycleEvents.SERVER_STARTING.register(EndlessHeights::loadPersistedRange);
        ServerLifecycleEvents.SERVER_STARTED.register(EndlessHeights::syncWorldData);
    }

    @Override
    public void onInitializeClient() {
        // Client-specific initialization code
        Endless.clientInit();

        ClientPlayNetworking.registerGlobalReceiver(HEIGHT_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            int min = buf.readVarInt();
            int max = buf.readVarInt();
            // Runs before the login packet creates the client world.
            client.execute(() -> EndlessHeights.applyEffective(min, max));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            EndlessHeights.resetToLocalConfig());
    }

    @Override
    public void onInitializeServer() {
        // Server-specific initialization code
        Endless.serverInit();
    }
}
