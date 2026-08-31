package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.network.EndlessNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge-specific implementation of the Endless mod.
 */
@Mod(Endless.MOD_ID)
public class EndlessForge {

    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;

    public EndlessForge() {
        // Register the setup method for mod loading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

        // Register ourselves for server and other game events
        MinecraftForge.EVENT_BUS.register(this);

        channel = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Endless.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        channel.registerMessage(0, SyncHeightPacket.class,
            SyncHeightPacket::encode, SyncHeightPacket::decode, SyncHeightPacket::handle);
    }

    private void setup(final FMLCommonSetupEvent event) {
        // Common setup code
        Endless.init();

        event.enqueueWork(() -> EndlessNetworking.registerSender((player, min, max) ->
            channel.send(PacketDistributor.PLAYER.with(() -> player), new SyncHeightPacket(min, max))));
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Client-specific setup code
        Endless.clientInit();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Server-specific setup code
        Endless.serverInit();
        // Worlds exist and no player has joined yet: apply the world-persisted
        // range so every chunk packet uses it.
        EndlessHeights.applyWorldRange(event.getServer());
    }
}
