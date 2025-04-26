package com.nstut.endless.fabric;

import com.nstut.endless.EndlessMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * Fabric-specific implementation of the Endless mod.
 */
public class EndlessFabric implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer {

    @Override
    public void onInitialize() {
        // Common initialization code
        EndlessMod.init();
    }

    @Override
    public void onInitializeClient() {
        // Client-specific initialization code
        EndlessMod.clientInit();
    }

    @Override
    public void onInitializeServer() {
        // Server-specific initialization code
        EndlessMod.serverInit();
    }
}
