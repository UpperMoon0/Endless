package com.nstut.endless.forge.compat;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge's outer render boundary is shared by terrain and shader shadow passes. */
@Mod.EventBusSubscriber(modid = "endless", value = Dist.CLIENT)
public final class EmbeddiumFrameClock {
    private static long frame;
    private EmbeddiumFrameClock() {}
    public static long frame() { return frame; }
    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) frame++;
    }
}
