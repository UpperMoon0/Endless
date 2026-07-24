package com.nstut.endless.mixin;

import com.nstut.endless.storage.VerticalSectionManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

    @Shadow @Final
    private ServerLevel level;

    @Unique
    private int endless$purgeTimer;

    private static final int PURGE_INTERVAL_TICKS = 200;

    @Inject(method = "tickChunks", at = @At("TAIL"))
    private void onTickChunks(CallbackInfo ci) {
        endless$purgeTimer++;
        if (endless$purgeTimer < PURGE_INTERVAL_TICKS) return;
        endless$purgeTimer = 0;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        ServerChunkCache self = (ServerChunkCache) (Object) this;
        for (ServerPlayer player : players) {
            int cx = player.blockPosition().getX() >> 4;
            int cz = player.blockPosition().getZ() >> 4;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    net.minecraft.world.level.chunk.ChunkAccess ca =
                        self.getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
                    if (ca instanceof LevelChunk chunk && chunk != null) {
                        VerticalSectionManager.purgeDistantSections(chunk, players);
                    }
                }
            }
        }
    }
}
