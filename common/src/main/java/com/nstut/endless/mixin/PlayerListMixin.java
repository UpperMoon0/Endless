package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge-only enforcement point. The Fabric entry point is
 * {@code EndlessFabric.onInitialize}, which performs the initial range sync
 * during the login phase; for Fabric players this mixin is a no-op because
 * {@link com.nstut.endless.network.EndlessNetworking#shouldEnforceRange}
 * returns false once a login-phase sync is registered.
 *
 * <p>For Forge the earliest deterministic point in the play phase where the
 * player has a connection and the server has not yet sent the login packet
 * is the {@code sendLevelInfo} call inside {@code placeNewPlayer}; the range
 * is delivered through the play channel there. Chunk packets serialize
 * sections without Y coordinates, so a client that does not know the
 * effective range would map section payloads to the wrong Y positions.</p>
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(
        method = "placeNewPlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;sendLevelInfo(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;)V"
        )
    )
    private void endless$syncHeights(Connection connection, ServerPlayer player, CallbackInfo ci) {
        EndlessHeights.syncOnJoin(player);
    }
}
