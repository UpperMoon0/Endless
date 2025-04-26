package com.nstut.endless;

import com.nstut.endless.config.EndlessConfig;
/**
 * Main entry point for the Endless mod.
 */
public class EndlessMod {
    public static final String MOD_ID = "endless";
    
    /**
     * Initialize the mod.
     */
    public static void init() {
        System.out.println("Initializing Endless mod");
        // Load config
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
