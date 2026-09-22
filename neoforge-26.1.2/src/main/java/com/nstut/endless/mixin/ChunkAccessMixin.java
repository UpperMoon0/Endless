package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import com.nstut.endless.vertical.VerticalPageLayout;
import com.nstut.endless.vertical.VerticalPagePos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes direct ChunkAccess consumers see sparse blocks without widening dense arrays. */
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

    /**
     * Vanilla clamps this query into the dense build range. RenderRegionCache and
     * PathNavigationRegion use it before asking for individual block states, so
     * that clamp would make every sparse section look empty and cull it before
     * Endless' LevelChunk#getBlockState routing can run.
     *
     * <p>The sparse test is deliberately page-conservative: if a 512-block page
     * intersecting the requested interval exists, the interval is treated as
     * potentially non-empty. That can compile an extra render section, but can
     * never hide real sparse blocks and avoids scanning/decoding an entire page
     * merely for an emptiness preflight.</p>
     */
    @Inject(method = "isYSpaceEmpty", at = @At("HEAD"), cancellable = true)
    private void endless$isYSpaceEmpty(int minY, int maxY, CallbackInfoReturnable<Boolean> cir) {
        if (!EndlessLogicalHeights.isActive() || !((Object) this instanceof LevelChunk chunk)) {
            return;
        }
        if (minY > maxY) {
            cir.setReturnValue(true);
            return;
        }

        int denseMin = chunk.getMinBuildHeight();
        int denseMax = chunk.getMaxBuildHeight() - 1;
        int denseFrom = Math.max(minY, denseMin);
        int denseTo = Math.min(maxY, denseMax);
        if (denseFrom <= denseTo) {
            int firstSection = Math.floorDiv(denseFrom, 16);
            int lastSection = Math.floorDiv(denseTo, 16);
            for (int sectionY = firstSection; sectionY <= lastSection; sectionY++) {
                int index = chunk.getSectionIndexFromSectionY(sectionY);
                if (index >= 0 && index < chunk.getSections().length
                    && !chunk.getSection(index).hasOnlyAir()) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        }

        MinecraftVerticalWorld sparse = EndlessVerticalEngine.world(chunk.getLevel());
        if (endless$hasSparsePage(chunk, sparse, minY, Math.min(maxY, denseMin - 1))
            || endless$hasSparsePage(chunk, sparse, Math.max(minY, denseMax + 1), maxY)) {
            cir.setReturnValue(false);
            return;
        }
        cir.setReturnValue(true);
    }

    @Unique
    private static boolean endless$hasSparsePage(
        LevelChunk chunk,
        MinecraftVerticalWorld sparse,
        int fromY,
        int toY
    ) {
        int logicalMin = EndlessHeights.getMinBuildHeight();
        int logicalMax = EndlessHeights.getMaxBuildHeight();
        if (fromY > toY || toY < logicalMin || fromY >= logicalMax) {
            return false;
        }
        int boundedFrom = Math.max(fromY, logicalMin);
        int boundedTo = Math.min(toY, logicalMax - 1);
        int firstPage = VerticalPageLayout.pageYForBlockY(boundedFrom);
        int lastPage = VerticalPageLayout.pageYForBlockY(boundedTo);
        for (int pageY = firstPage; pageY <= lastPage; pageY++) {
            if (sparse.pageExists(new VerticalPagePos(
                chunk.getPos().x, pageY, chunk.getPos().z))) {
                return true;
            }
        }
        return false;
    }
}
