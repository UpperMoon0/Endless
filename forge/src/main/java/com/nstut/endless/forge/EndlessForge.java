package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge-specific implementation of the Endless mod.
 */
@Mod(Endless.MOD_ID)
public class EndlessForge {

    public EndlessForge() {
        // Register the setup method for mod loading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

        // Register ourselves for server and other game events
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void setup(final FMLCommonSetupEvent event) {
        // Common setup code
        Endless.init();
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        // Client-specific setup code
        Endless.clientInit();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Server-specific setup code
        Endless.serverInit();
    }
}
