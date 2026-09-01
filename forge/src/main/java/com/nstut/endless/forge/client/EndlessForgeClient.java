package com.nstut.endless.forge.client;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only event handlers for Forge. Kept in a separate class so dedicated
 * servers never load client-only event types.
 */
@Mod.EventBusSubscriber(modid = Endless.MOD_ID, value = Dist.CLIENT)
public final class EndlessForgeClient {

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        EndlessHeights.resetToLocalConfig();
    }
}
