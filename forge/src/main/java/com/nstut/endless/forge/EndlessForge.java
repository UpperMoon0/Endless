package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.network.EndlessNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
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
            () -> PROTOCOL,
            // acceptMissingOr: vanilla clients are not rejected here; the
            // join-time sync check in EndlessHeights.syncOnJoin rejects them
            // only when the world's range is extended.
            NetworkRegistry.acceptMissingOr(PROTOCOL::equals),
            NetworkRegistry.acceptMissingOr(PROTOCOL::equals));
        // Strictly server-to-client: the height sync is authoritative and must
        // never be accepted from a client (direction assertion + handler check).
        channel.registerMessage(0, SyncHeightPacket.class,
            SyncHeightPacket::encode, SyncHeightPacket::decode, SyncHeightPacket::handle,
            java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
    }

    private void setup(final FMLCommonSetupEvent event) {
        // Common setup code
        Endless.init();

        event.enqueueWork(() -> EndlessNetworking.registerSender(new EndlessNetworking.Sender() {
            @Override
            public boolean canSend(ServerPlayer player) {
                return channel.isRemotePresent(player.connection.connection);
            }

            @Override
            public void send(ServerPlayer player, int min, int max) {
                channel.send(PacketDistributor.PLAYER.with(() -> player), new SyncHeightPacket(min, max));
            }
        }));
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Client-specific setup code
        Endless.clientInit();
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Server-specific setup code
        Endless.serverInit();
        // Fires before levels are created: read the world's persisted range so
        // chunk deserialization uses it from the very first chunk.
        EndlessHeights.loadPersistedRange(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Worlds are loaded: mirror the effective range into SavedData.
        EndlessHeights.syncWorldData(event.getServer());
    }
}
