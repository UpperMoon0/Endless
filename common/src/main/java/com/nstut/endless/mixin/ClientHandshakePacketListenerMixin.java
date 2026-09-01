package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
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

/**
 * Establishes the vanilla build range for every newly constructed remote
 * client login connection.
 *
 * <p>{@code ClientboundHelloPacket} is authentication-specific and is skipped
 * by offline-mode servers, so it is not a valid every-connection boundary.
 * Vanilla constructs {@link ClientHandshakePacketListenerImpl} for the login
 * connection regardless of whether encryption/authentication later happens.
 * Injecting at constructor RETURN therefore resets stale state before any
 * login packet can advance the connection.</p>
 *
 * <p>Memory connections are integrated-server/singleplayer connections. They
 * share the server's already-authoritative effective range in this JVM and
 * must not be reset. Remote network connections always start from vanilla;
 * Fabric/Forge login sync may then overwrite the baseline before
 * {@code ClientboundLoginPacket} constructs the client world.</p>
 *
 * <p>Registered in the mixin config's client section, so it never loads on
 * dedicated servers.</p>
 */
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
        EndlessHeights.applyVanillaBaselineForNewConnection(connection.isMemoryConnection());
    }
}
