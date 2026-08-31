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
 * Sends the authoritative build range to the player at the {@code sendLevelInfo}
 * call inside {@code placeNewPlayer}: by that point the
 * {@code ServerGamePacketListenerImpl} exists ({@code player.connection} is
 * assigned), and it fires before the login packet and any chunk packet.
 * Chunk packets serialize sections without Y coordinates, so a client with a
 * different range would map section payloads to the wrong Y positions.
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
