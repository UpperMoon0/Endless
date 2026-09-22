package com.nstut.endless.mixin;

import com.nstut.endless.vertical.VerticalNetworkBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends sparse vertical pages immediately after vanilla sends a horizontal chunk. */
@Mixin(PlayerChunkSender.class)
public class ChunkMapMixin {
    @Inject(method = "sendChunk", at = @At("TAIL"))
    private static void endless$playerLoadedChunk(
        ServerGamePacketListenerImpl connection,
        ServerLevel level,
        LevelChunk chunk,
        CallbackInfo ci
    ) {
        VerticalNetworkBridge.sendVisiblePagesForChunk(connection.player, chunk);
    }
}
