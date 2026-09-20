package com.nstut.endless.fabric;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Fabric 1.21.1 bootstrap using the configuration phase for pre-world height sync. */
public final class EndlessFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Endless.init();

        PayloadTypeRegistry.configurationS2C().register(HeightSyncPayload.TYPE, HeightSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VerticalPagePayload.TYPE, VerticalPagePayload.CODEC);

        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (!EndlessLogicalHeights.isActive()) {
                return;
            }
            if (!ServerConfigurationNetworking.canSend(handler, HeightSyncPayload.TYPE)) {
                handler.disconnect(net.minecraft.network.chat.Component.literal(
                    "This server requires Endless v0.5+ for sparse infinite-height worlds."));
                return;
            }
            ServerConfigurationNetworking.send(handler, HeightSyncPayload.current());
        });

        VerticalNetworkBridge.registerSender((player, snapshot) ->
            ServerPlayNetworking.send(player, new VerticalPagePayload(snapshot)));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            EndlessHeights.loadPersistedRange(server);
            EndlessLogicalHeights.activate();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(EndlessHeights::syncWorldData);
        ServerTickEvents.END_SERVER_TICK.register(VerticalNetworkBridge::tickServer);
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            EndlessVerticalEngine.unloadColumn(level, chunk.getPos().x, chunk.getPos().z);
            ExtendedPoiStorage.unload(level, chunk.getPos());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            VerticalNetworkBridge.shutdown();
            EndlessLogicalHeights.deactivate();
        });
    }

    public record HeightSyncPayload(
        int logicalMin, int logicalMax, int denseMin, int denseMax, int envelopeMin, int envelopeMax
    ) implements CustomPacketPayload {
        public static final Type<HeightSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Endless.MOD_ID, "height_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HeightSyncPayload> CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), HeightSyncPayload::read);

        static HeightSyncPayload current() {
            return new HeightSyncPayload(
                EndlessHeights.getMinBuildHeight(), EndlessHeights.getMaxBuildHeight(),
                EndlessHeights.getDenseMinBuildHeight(), EndlessHeights.getDenseMaxBuildHeight(),
                EndlessLogicalHeights.MIN_BUILD_HEIGHT, EndlessLogicalHeights.MAX_BUILD_HEIGHT);
        }

        static HeightSyncPayload read(RegistryFriendlyByteBuf buf) {
            return new HeightSyncPayload(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(logicalMin);
            buf.writeVarInt(logicalMax);
            buf.writeVarInt(denseMin);
            buf.writeVarInt(denseMax);
            buf.writeVarInt(envelopeMin);
            buf.writeVarInt(envelopeMax);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VerticalPagePayload(VerticalPageSnapshot snapshot) implements CustomPacketPayload {
        public static final Type<VerticalPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Endless.MOD_ID, "vertical_page"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VerticalPagePayload> CODEC =
            StreamCodec.of((buf, payload) -> payload.snapshot.write(buf),
                buf -> new VerticalPagePayload(VerticalPageSnapshot.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
