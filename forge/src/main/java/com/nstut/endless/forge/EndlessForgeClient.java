package com.nstut.endless.forge;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Client-only Forge event handlers. Registered only when
 * {@code FMLEnvironment.dist} is the client: {@code ClientPlayerNetworkEvent}
 * references client classes ({@code LocalPlayer}, {@code
 * MultiPlayerGameMode}), so subscribing from the shared mod class would
 * attempt to load them on a dedicated server and crash mod construction.
 */
public final class EndlessForgeClient {

    public static void register() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(new EndlessForgeClient());
        }
    }

    private EndlessForgeClient() {
    }

    @SubscribeEvent
    public void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // Mirror the Fabric client: drop the applied range so the local file
        // config is used again when joining the next server.
        EndlessHeights.resetToLocalConfig();
    }
}
