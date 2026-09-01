package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.vertical.VerticalNetworkBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Forge-specific implementation of the Endless mod. */
@Mod(Endless.MOD_ID)
public class EndlessForge {
    private static final String PROTOCOL = "3";
    private static SimpleChannel channel;

    public EndlessForge() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);
        EndlessForgeClient.register();

        channel = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Endless.MOD_ID, "main"),
            () -> PROTOCOL,
            EndlessForge::clientAcceptsVersions,
            EndlessForge::serverAcceptsVersions);

        channel.messageBuilder(SyncHeightPacket.class, 1, NetworkDirection.LOGIN_TO_CLIENT)
            .loginIndex(SyncHeightPacket::getLoginIndex, SyncHeightPacket::setLoginIndex)
            .decoder(SyncHeightPacket::decode)
            .encoder(SyncHeightPacket::encode)
            .markAsLoginPacket()
            .noResponse()
            .consumerNetworkThread(SyncHeightPacket::handle)
            .add();

        channel.messageBuilder(VerticalPageForgePacket.class, 2, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(VerticalPageForgePacket::decode)
            .encoder(VerticalPageForgePacket::encode)
            .consumerNetworkThread(VerticalPageForgePacket::handle)
            .add();

        VerticalNetworkBridge.registerSender((player, snapshot) ->
            channel.send(
                PacketDistributor.PLAYER.with(() -> player),
                new VerticalPageForgePacket(snapshot)));
    }

    private static boolean clientAcceptsVersions(String version) {
        return NetworkRegistry.ABSENT.version().equals(version) || PROTOCOL.equals(version);
    }

    private static boolean serverAcceptsVersions(String version) {
        return PROTOCOL.equals(version);
    }

    private void setup(final FMLCommonSetupEvent event) {
        Endless.init();
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        Endless.clientInit();
        LiveJoinTest.preseedStaleRangeIfRequested();
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        Endless.serverInit();
        EndlessHeights.loadPersistedRange(event.getServer());
        EndlessLogicalHeights.activate();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        EndlessHeights.syncWorldData(event.getServer());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VerticalNetworkBridge.tickServer(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VerticalNetworkBridge.shutdown();
        EndlessLogicalHeights.deactivate();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LiveJoinTest.tick();
    }
}
