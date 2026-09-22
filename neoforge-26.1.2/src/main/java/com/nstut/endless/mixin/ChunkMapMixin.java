package com.nstut.endless.mixin;

import com.nstut.endless.vertical.VerticalNetworkBridge;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends sparse vertical pages immediately after vanilla sends a horizontal chunk. */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {
    @Inject(method = "playerLoadedChunk", at = @At("TAIL"))
    private void endless$playerLoadedChunk(
        ServerPlayer player,
        MutableObject<ClientboundLevelChunkWithLightPacket> cachedPacket,
        LevelChunk chunk,
        CallbackInfo ci
    ) {
        VerticalNetworkBridge.sendVisiblePagesForChunk(player, chunk);
    }
}
