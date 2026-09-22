package com.nstut.endless.neoforge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** NeoForge 1.21.1 bootstrap. */
@Mod(Endless.MOD_ID)
public final class EndlessNeoForge {
    private static final int PROTOCOL = 5;

    public EndlessNeoForge(IEventBus modBus) {
        Endless.init();
        modBus.addListener(this::registerPayloads);
        modBus.addListener(RegisterConfigurationTasksEvent.class, event ->
            event.register(new HeightSyncTask()));

        VerticalNetworkBridge.registerSender((player, snapshot) ->
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new VerticalPagePayload(snapshot)));

        NeoForge.EVENT_BUS.addListener(this::serverAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::chunkUnload);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Endless.MOD_ID);
        registrar.configurationToClient(HeightSyncPayload.TYPE, HeightSyncPayload.CODEC,
            HeightSyncPayload::handle);
        registrar.configurationToServer(HeightAckPayload.TYPE, HeightAckPayload.CODEC,
            HeightAckPayload::handle);
        registrar.playToClient(VerticalPagePayload.TYPE, VerticalPagePayload.CODEC,
            VerticalPagePayload::handle);
    }

    private void serverAboutToStart(ServerAboutToStartEvent event) {
        Endless.serverInit();
        EndlessHeights.loadPersistedRange(event.getServer());
        EndlessLogicalHeights.activate();
    }

    private void serverStarted(ServerStartedEvent event) {
        EndlessHeights.syncWorldData(event.getServer());
    }

    private void serverTick(ServerTickEvent.Post event) {
        VerticalNetworkBridge.tickServer(event.getServer());
    }

    private void chunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            EndlessVerticalEngine.unloadColumn(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
            ExtendedPoiStorage.unload(level, event.getChunk().getPos());
        } else if (event.getLevel() instanceof net.minecraft.world.level.Level level) {
            EndlessVerticalEngine.unloadColumn(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    private void serverStopping(ServerStoppingEvent event) {
        VerticalNetworkBridge.shutdown();
        EndlessLogicalHeights.deactivate();
    }

    public record HeightSyncTask() implements ICustomConfigurationTask {
        public static final ConfigurationTask.Type TYPE =
            new ConfigurationTask.Type("endless:height_sync");

        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            sender.accept(HeightSyncPayload.current());
        }

        @Override
        public Type type() {
            return TYPE;
        }
    }

    public record HeightSyncPayload(
        int logicalMin, int logicalMax, int denseMin, int denseMax,
        int envelopeMin, int envelopeMax, int protocol
    ) implements CustomPacketPayload {
        public static final Type<HeightSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Endless.MOD_ID, "height_sync"));
        public static final StreamCodec<FriendlyByteBuf, HeightSyncPayload> CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), HeightSyncPayload::read);

        static HeightSyncPayload current() {
            return new HeightSyncPayload(
                EndlessHeights.getMinBuildHeight(), EndlessHeights.getMaxBuildHeight(),
                EndlessHeights.getDenseMinBuildHeight(), EndlessHeights.getDenseMaxBuildHeight(),
                EndlessLogicalHeights.MIN_BUILD_HEIGHT, EndlessLogicalHeights.MAX_BUILD_HEIGHT,
                PROTOCOL);
        }

        static HeightSyncPayload read(FriendlyByteBuf buf) {
            return new HeightSyncPayload(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(logicalMin);
            buf.writeVarInt(logicalMax);
            buf.writeVarInt(denseMin);
            buf.writeVarInt(denseMax);
            buf.writeVarInt(envelopeMin);
            buf.writeVarInt(envelopeMax);
            buf.writeVarInt(protocol);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(HeightSyncPayload payload, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (payload.protocol != PROTOCOL
                    || payload.envelopeMin != EndlessLogicalHeights.MIN_BUILD_HEIGHT
                    || payload.envelopeMax != EndlessLogicalHeights.MAX_BUILD_HEIGHT) {
                    throw new IllegalStateException("Server uses an incompatible Endless height protocol");
                }
                EndlessHeights.applyEffective(
                    payload.logicalMin, payload.logicalMax, payload.denseMin, payload.denseMax);
                EndlessLogicalHeights.activate();
            }).thenRun(() -> context.reply(new HeightAckPayload(PROTOCOL)));
        }
    }

    public record HeightAckPayload(int protocol) implements CustomPacketPayload {
        public static final Type<HeightAckPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Endless.MOD_ID, "height_ack"));
        public static final StreamCodec<FriendlyByteBuf, HeightAckPayload> CODEC =
            StreamCodec.of((buf, payload) -> buf.writeVarInt(payload.protocol),
                buf -> new HeightAckPayload(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(HeightAckPayload payload, IPayloadContext context) {
            if (payload.protocol != PROTOCOL) {
                context.disconnect(net.minecraft.network.chat.Component.literal(
                    "Client uses an incompatible Endless height protocol."));
                return;
            }
            context.finishCurrentTask(HeightSyncTask.TYPE);
        }
    }

    public record VerticalPagePayload(VerticalPageSnapshot snapshot) implements CustomPacketPayload {
        public static final Type<VerticalPagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Endless.MOD_ID, "vertical_page"));
        public static final StreamCodec<FriendlyByteBuf, VerticalPagePayload> CODEC =
            StreamCodec.of((buf, payload) -> payload.snapshot.write(buf),
                buf -> new VerticalPagePayload(VerticalPageSnapshot.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(VerticalPagePayload payload, IPayloadContext context) {
            context.enqueueWork(() -> EndlessNeoForgeClient.applyVerticalPage(payload.snapshot));
        }
    }
}
