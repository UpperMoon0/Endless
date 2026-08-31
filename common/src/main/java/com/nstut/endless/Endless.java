package com.nstut.endless;

import com.nstut.endless.config.EndlessConfig;

/**
 * Main entry point for the Endless mod.
 */
public class Endless {
    public static final String MOD_ID = "endless";

    private static boolean initialized;

    /**
     * Initialize the mod. Safe to call multiple times: Fabric calls both the mod
     * and client/server initializers on the same launch, and Forge calls the
     * common setup plus client setup on the client.
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        System.out.println("Initializing Endless mod");
        EndlessConfig.getInstance().load();
    }

    /**
     * Called when the mod is being initialized on the client side.
     */
    public static void clientInit() {
        init();
    }

    /**
     * Called when the mod is being initialized on the server side.
     */
    public static void serverInit() {
        init();
    }
}
