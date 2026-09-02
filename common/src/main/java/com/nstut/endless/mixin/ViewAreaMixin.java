package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera-following 512-block render window for the sparse vertical engine. */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {
    @Unique private static final int RENDER_WINDOW_SECTIONS = 32;
    @Unique private static final int REBASE_HYSTERESIS_SECTIONS = 8;
    @Unique private static final int UNINITIALIZED = Integer.MIN_VALUE;

    @Shadow @Final protected Level level;
    @Shadow protected int chunkGridSizeX;
    @Shadow protected int chunkGridSizeY;
    @Shadow protected int chunkGridSizeZ;
    @Shadow @Final public ChunkRenderDispatcher.RenderChunk[] chunks;
    @Shadow protected abstract int getChunkIndex(int x, int y, int z);

    @Unique private int endless$windowBaseSection = UNINITIALIZED;

    @Redirect(
        method = "setViewDistance",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionsCount()I")
    )
    private int endless$capRenderSections(Level level) {
        return EndlessLogicalHeights.isActive()
            ? RENDER_WINDOW_SECTIONS
            : Math.min(level.getSectionsCount(), RENDER_WINDOW_SECTIONS);
    }

    @Inject(method = "repositionCamera", at = @At("HEAD"))
    private void endless$trackCameraSection(double x, double z, CallbackInfo ci) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        int cameraSection = Math.floorDiv(camera.getBlockPosition().getY(), 16);
        int minSection = EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.minSection()
            : this.level.getMinSection();
        int maxSectionExclusive = EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.maxSectionExclusive()
            : this.level.getMaxSection();
        int available = maxSectionExclusive - minSection;

        if (available <= chunkGridSizeY) {
            endless$windowBaseSection = minSection;
            return;
        }

        if (endless$windowBaseSection != UNINITIALIZED) {
            int center = endless$windowBaseSection + (chunkGridSizeY / 2);
            if (Math.abs(cameraSection - center) <= REBASE_HYSTERESIS_SECTIONS) {
                return;
            }
        }

        endless$windowBaseSection = Math.max(minSection, Math.min(
            cameraSection - (chunkGridSizeY / 2),
            maxSectionExclusive - chunkGridSizeY));
    }

    @Redirect(
        method = "repositionCamera",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I")
    )
    private int endless$shiftGridBase(Level level) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            endless$windowBaseSection = level.getMinSection();
        }
        return endless$windowBaseSection << 4;
    }

    @Inject(method = "getRenderChunkAt", at = @At("HEAD"), cancellable = true)
    private void endless$getRenderChunkAt(BlockPos pos, CallbackInfoReturnable<ChunkRenderDispatcher.RenderChunk> cir) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            return;
        }
        int ySection = Math.floorDiv(pos.getY(), 16) - endless$windowBaseSection;
        if (ySection < 0 || ySection >= chunkGridSizeY) {
            cir.setReturnValue(null);
            return;
        }
        int xSection = Mth.positiveModulo(Math.floorDiv(pos.getX(), 16), chunkGridSizeX);
        int zSection = Mth.positiveModulo(Math.floorDiv(pos.getZ(), 16), chunkGridSizeZ);
        cir.setReturnValue(this.chunks[this.getChunkIndex(xSection, ySection, zSection)]);
    }

    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void endless$setDirty(int x, int y, int z, boolean dirty, CallbackInfo ci) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            return;
        }
        ci.cancel();
        int ySection = y - endless$windowBaseSection;
        if (ySection < 0 || ySection >= chunkGridSizeY) {
            return;
        }
        int xSection = Math.floorMod(x, chunkGridSizeX);
        int zSection = Math.floorMod(z, chunkGridSizeZ);
        this.chunks[this.getChunkIndex(xSection, ySection, zSection)].setDirty(dirty);
    }
}
