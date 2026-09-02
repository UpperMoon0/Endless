package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.ExtendedPoiStorage;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Forge common/server bootstrap; client code is dist-isolated. */
@Mod(Endless.MOD_ID)
public final class EndlessForge {
    private static final String PROTOCOL = "3";
    private static SimpleChannel channel;

    public EndlessForge() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::setup);
        MinecraftForge.EVENT_BUS.register(this);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientBootstrap.register(modBus));
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(Endless.MOD_ID, "main"),
            () -> PROTOCOL, EndlessForge::clientAcceptsVersions, EndlessForge::serverAcceptsVersions);
        channel.messageBuilder(SyncHeightPacket.class, 1, NetworkDirection.LOGIN_TO_CLIENT)
            .loginIndex(SyncHeightPacket::getLoginIndex, SyncHeightPacket::setLoginIndex)
            .decoder(SyncHeightPacket::decode).encoder(SyncHeightPacket::encode)
            .markAsLoginPacket().noResponse().consumerNetworkThread(SyncHeightPacket::handle).add();
        channel.messageBuilder(VerticalPageForgePacket.class, 2, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(VerticalPageForgePacket::decode).encoder(VerticalPageForgePacket::encode)
            .consumerNetworkThread(VerticalPageForgePacket::handle).add();
        VerticalNetworkBridge.registerSender((player, snapshot) ->
            channel.send(PacketDistributor.PLAYER.with(() -> player), new VerticalPageForgePacket(snapshot)));
    }

    private static boolean clientAcceptsVersions(String version) {
        return NetworkRegistry.ABSENT.version().equals(version) || PROTOCOL.equals(version);
    }
    private static boolean serverAcceptsVersions(String version) { return PROTOCOL.equals(version); }
    private void setup(FMLCommonSetupEvent event) { Endless.init(); }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        Endless.serverInit();
        EndlessHeights.loadPersistedRange(event.getServer());
        EndlessLogicalHeights.activate();
    }
    @SubscribeEvent public void onServerStarted(ServerStartedEvent event) { EndlessHeights.syncWorldData(event.getServer()); }
    @SubscribeEvent public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) VerticalNetworkBridge.tickServer(event.getServer());
    }
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            EndlessVerticalEngine.unloadColumn(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
            ExtendedPoiStorage.unload(level, event.getChunk().getPos());
        } else if (event.getLevel() instanceof net.minecraft.world.level.Level level) {
            EndlessVerticalEngine.unloadColumn(level, event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VerticalNetworkBridge.shutdown();
        EndlessLogicalHeights.deactivate();
    }
    private static final class ClientBootstrap {
        private static void register(net.minecraftforge.eventbus.api.IEventBus modBus) { EndlessForgeClient.register(modBus); }
    }
}
