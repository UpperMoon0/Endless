package com.nstut.endless.neoforge;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.testing.LiveSameJvmRejoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = com.nstut.endless.Endless.MOD_ID, value = Dist.CLIENT)
public final class EndlessNeoForgeClient {
    private EndlessNeoForgeClient() {}

    static void applyVerticalPage(VerticalPageSnapshot snapshot) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !EndlessLogicalHeights.isActive()) return;
        com.nstut.endless.vertical.VerticalClientUpdates.apply(client, snapshot);
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        if (LiveJoinTest.isArmed()) LiveJoinTest.tick();
        if (LiveSameJvmRejoinTest.isArmed()) LiveSameJvmRejoinTest.tick();
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        var level = Minecraft.getInstance().level;
        if (level != null) EndlessVerticalEngine.close(level);
    }
}
