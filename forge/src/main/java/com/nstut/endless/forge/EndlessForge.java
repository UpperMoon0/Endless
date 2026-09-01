package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.testing.LiveJoinTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge-specific implementation of the Endless mod.
 *
 * <p>The authoritative build range reaches the client during the FML login
 * handshake, never in the play phase. The initial play packet
 * ({@code ClientboundLoginPacket}) constructs the client world, so anything
 * delivered after it is too late: chunk section payloads on the wire carry no
 * Y coordinates and would be mapped to the wrong Y positions on a client
 * whose effective range has not been applied yet. Two Forge-native login
 * mechanisms are used:</p>
 *
 * <ul>
 *   <li><b>Vanilla-client gating.</b> The channel's server-side version
 *       predicate only accepts vanilla clients (Forge sends the
 *       {@code ACCEPTVANILLA} marker to test this) while the world's
 *       effective range is vanilla. On an extended-range server the predicate
 *       rejects the marker, so {@code ServerLifecycleHooks} disconnects
 *       vanilla clients before the FML handshake even starts.</li>
 *   <li><b>Range delivery.</b> {@link SyncHeightPacket} is registered with
 *       {@code markAsLoginPacket()}: Forge gathers it once per connection
 *       during the NEGOTIATING state and sends it before the login advances,
 *       so the client applies the range before the world is constructed.</li>
 * </ul>
 */
@Mod(Endless.MOD_ID)
public class EndlessForge {

    /**
     * Bumped to 2 when the play-phase sync packet was replaced by the
     * login-phase packet; old clients (protocol 1) cannot receive the login
     * packet and would desync silently, so they are rejected at negotiation.
     */
    private static final String PROTOCOL = "2";
    private static SimpleChannel channel;

    public EndlessForge() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(this);
        // Client-only handlers (ClientPlayerNetworkEvent etc.) must not be
        // loaded on a dedicated server.
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
    }

    /**
     * Client side: accept the server's protocol when it matches; a server
     * without the Endless channel (vanilla server) is accepted and the client
     * keeps its local config. Any other value means an incompatible Endless
     * protocol and rejects the connection during login.
     */
    private static boolean clientAcceptsVersions(String version) {
        return NetworkRegistry.ABSENT.version().equals(version) || PROTOCOL.equals(version);
    }

    /**
     * Server side: modded Endless clients must speak the current protocol.
     * Vanilla clients (Forge sends the ACCEPTVANILLA marker) and modded
     * clients without the channel are only admitted while the world's
     * effective range is vanilla; an extended range on the wire has no Y
     * coordinates per section, so such a client would map section payloads to
     * the wrong Y positions.
     */
    private static boolean serverAcceptsVersions(String version) {
        if (PROTOCOL.equals(version)) {
            return true;
        }
        boolean vanillaRange = EndlessHeights.getMinBuildHeight() == EndlessHeights.VANILLA_MIN_BUILD_HEIGHT
            && EndlessHeights.getMaxBuildHeight() == EndlessHeights.VANILLA_MAX_BUILD_HEIGHT;
        return vanillaRange
            && (NetworkRegistry.ACCEPTVANILLA.equals(version)
                || NetworkRegistry.ABSENT.version().equals(version));
    }

    private void setup(final FMLCommonSetupEvent event) {
        Endless.init();
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        Endless.clientInit();
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Server-specific setup code
        Endless.serverInit();
        // Fires before levels are created: read the world's persisted range so
        // chunk deserialization and login-packet gathering both use it.
        EndlessHeights.loadPersistedRange(event.getServer());
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Worlds are loaded: mirror the effective range into SavedData.
        EndlessHeights.syncWorldData(event.getServer());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        // TickEvent.ClientTickEvent carries no client classes and is safe to
        // reference on a dedicated server; the live-join hook no-ops unless
        // armed. Dist-unsafe handlers live in EndlessForgeClient.
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        LiveJoinTest.tick();
    }
}
