package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Establishes the vanilla build range at the start of every client login
 * phase ({@code handleHello} is the first handler of the handshake, before
 * either loader's Endless login exchange). Every remote connection therefore
 * begins from vanilla: an extended local file config cannot leak into a
 * world, and a range applied during a previous connection's login handshake
 * cannot survive a mid-login rejection (the logout hooks only cover
 * connections that reached the player stage, so the reset must happen when a
 * connection STARTS, not only when one ends).
 *
 * <p>Singleplayer connections are excluded: the integrated server owns the
 * effective range and has already applied the world's persisted range before
 * the client's login phase begins, so the shared static must not be
 * clobbered. The server's authoritative range still overwrites the baseline
 * during login whenever the Endless login exchange runs, and
 * {@link ClientPacketListenerMixin} keeps a final fallback at the login
 * packet itself.</p>
 *
 * <p>Registered in the mixin config's client section, so it never loads on
 * dedicated servers.</p>
 */
@Mixin(ClientHandshakePacketListenerImpl.class)
public class ClientHandshakePacketListenerMixin {

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void endless$startConnectionOnVanillaBaseline(ClientboundHelloPacket packet, CallbackInfo ci) {
        EndlessHeights.applyVanillaBaselineForNewConnection(
            Minecraft.getInstance().getSingleplayerServer() != null);
    }
}
