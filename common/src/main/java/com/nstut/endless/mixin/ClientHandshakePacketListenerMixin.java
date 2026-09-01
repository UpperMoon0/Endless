package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.Duration;
import java.util.function.Consumer;

/** Clears stale server-authoritative height/protocol state at every remote login. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public class ClientHandshakePacketListenerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void endless$startConnectionOnVanillaBaseline(
        Connection connection,
        Minecraft minecraft,
        ServerData serverData,
        Screen parentScreen,
        boolean transferring,
        Duration worldLoadDuration,
        Consumer<Component> statusConsumer,
        CallbackInfo ci
    ) {
        if (!connection.isMemoryConnection()) {
            EndlessLogicalHeights.deactivate();
        }
        EndlessHeights.applyVanillaBaselineForNewConnection(connection.isMemoryConnection());
    }
}
