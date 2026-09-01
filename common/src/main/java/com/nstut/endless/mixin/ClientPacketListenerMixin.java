package com.nstut.endless.mixin;

import com.nstut.endless.testing.LiveJoinTest;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Live-join test hook only: asserts the authoritative build range at the
 * exact boundary where vanilla creates the client world, before any section
 * arrays are sized. Registered in the mixin config's client section, so it
 * never loads on dedicated servers, and the assertion body is a no-op unless
 * the {@code endless.liveJoinTest} system property is set.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void endless$assertLoginRange(ClientboundLoginPacket packet, CallbackInfo ci) {
        LiveJoinTest.assertPreLoginRange();
    }
}
