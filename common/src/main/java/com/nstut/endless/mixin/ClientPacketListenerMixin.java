package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.testing.LiveJoinTest;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client login-packet hook. Two responsibilities, in order:
 *
 * <ol>
 *   <li>Production: pin the vanilla build range as the baseline for remote
 *     connections ({@code applyVanillaBaselineIfUnapplied}) before vanilla
 *     constructs the client world. A server that provides no Endless range
 *     (vanilla server, or an Endless server with a vanilla world range)
 *     must never be joined with the local extended file config; the
 *     login-phase sync has already overwritten the baseline by this point
 *     when the server did provide a range. Singleplayer is unaffected: the
 *     integrated server applies its persisted range before login
 *     completes, so the baseline is a no-op there.</li>
 *   <li>Live-join test hook: asserts the authoritative build range at the
 *     exact boundary where vanilla creates the client world, before any
 *     section arrays are sized. The assertion body is a no-op unless the
 *     {@code endless.liveJoinTest} system property is set.</li>
 * </ol>
 *
 * <p>Registered in the mixin config's client section, so it never loads on
 * dedicated servers.</p>
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void endless$assertLoginRange(ClientboundLoginPacket packet, CallbackInfo ci) {
        // Baseline first, so the assertion below observes the value the
        // client world is actually about to be built with.
        EndlessHeights.applyVanillaBaselineIfUnapplied();
        LiveJoinTest.assertPreLoginRange();
    }
}
