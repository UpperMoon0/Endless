package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes direct LevelChunk heightmap consumers see sparse blocks as well. */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {
    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true)
    private void endless$getHeight(
        Heightmap.Types type,
        int localX,
        int localZ,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (!EndlessLogicalHeights.isActive() || !((Object) this instanceof LevelChunk chunk)) {
            return;
        }
        int x = chunk.getPos().getMinBlockX() + (localX & 15);
        int z = chunk.getPos().getMinBlockZ() + (localZ & 15);
        int sparseFirstAvailable = EndlessVerticalEngine.world(chunk.getLevel())
            .getExtendedHeight(type, x, z);
        if (sparseFirstAvailable != Integer.MIN_VALUE) {
            int sparseTop = sparseFirstAvailable - 1;
            if (sparseTop > cir.getReturnValue()) {
                cir.setReturnValue(sparseTop);
            }
        }
    }
}
