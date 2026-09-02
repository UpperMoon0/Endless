package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.Map;

/** Prevents full-chunk packets from truncating extended block-entity Y to short. */
@Mixin(ClientboundLevelChunkPacketData.class)
public class ClientboundLevelChunkPacketDataMixin {
    @Redirect(
        method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntities()Ljava/util/Map;"
        )
    )
    private Map<BlockPos, BlockEntity> endless$filterExtendedBlockEntities(LevelChunk chunk) {
        Map<BlockPos, BlockEntity> original = chunk.getBlockEntities();
        if (!EndlessLogicalHeights.isActive()) {
            return original;
        }

        Map<BlockPos, BlockEntity> filtered = new HashMap<>();
        original.forEach((pos, blockEntity) -> {
            if (!EndlessVerticalEngine.isExtendedY(chunk.getLevel(), pos.getY())) {
                filtered.put(pos, blockEntity);
            }
        });
        return filtered;
    }
}
