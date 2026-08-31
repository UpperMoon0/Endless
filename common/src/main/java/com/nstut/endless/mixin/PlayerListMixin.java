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
 * Sends the authoritative build range to the player at the very start of
 * {@code placeNewPlayer}, before the login packet and any chunk packet.
 * Chunk packets serialize sections without Y coordinates, so a client with a
 * different range would map section payloads to the wrong Y positions.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void endless$syncHeights(Connection connection, ServerPlayer player, CallbackInfo ci) {
        EndlessHeights.sendToPlayer(player);
    }
}
