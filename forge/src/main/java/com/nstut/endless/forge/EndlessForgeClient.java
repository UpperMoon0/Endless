package com.nstut.endless.forge;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Client-only Forge event handlers and sparse-page application. */
public final class EndlessForgeClient {
    public static void register() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(new EndlessForgeClient());
        }
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
    public void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) {
            EndlessVerticalEngine.close(client.level);
        }
        EndlessLogicalHeights.deactivate();
        EndlessHeights.resetToLocalConfig();
    }
}
