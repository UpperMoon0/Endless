package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(Endless.MOD_ID)
public final class EndlessForge {
    public EndlessForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(Endless.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Run our common setup.
        Endless.init();
    }
}
