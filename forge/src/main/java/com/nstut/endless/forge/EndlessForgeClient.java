package com.nstut.endless.forge;

import com.nstut.endless.Endless;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only Forge bootstrap and event handlers. */
public final class EndlessForgeClient {
    public static void register(IEventBus modBus) {
        modBus.addListener(EndlessForgeClient::clientSetup);
        MinecraftForge.EVENT_BUS.register(new EndlessForgeClient());
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        Endless.clientInit();
        LiveJoinTest.preseedStaleRangeIfRequested();
    }

    private EndlessForgeClient() {}

    public static void applyVerticalPage(VerticalPageSnapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !EndlessLogicalHeights.isActive()) {
            return;
        }
        EndlessVerticalEngine.world(client.level).applySnapshot(snapshot);
        client.levelRenderer.allChanged();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LiveJoinTest.tick();
        }
    }

    @SubscribeEvent
    public void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            EndlessVerticalEngine.close(client.level);
        }
        EndlessLogicalHeights.deactivate();
        EndlessHeights.resetToLocalConfig();
    }
}
