package com.nstut.endless.mixin;

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

/**
 * Vanilla anchors the render section grid to {@code minBuildHeight} and covers the
 * full configured height, allocating GPU render chunks for every section. At
 * extended heights that is millions of buffers, so the vertical grid is capped to
 * a window that follows the camera:
 *
 * <ul>
 *   <li>{@code setViewDistance} caps the vertical grid size.</li>
 *   <li>{@code repositionCamera} shifts the grid origin when the camera drifts too
 *       far from the window center; moved render chunks get a new origin, which
 *       marks them dirty and recompiles them (vanilla behavior via
 *       {@code RenderChunk#setOrigin}).</li>
 *   <li>{@code getRenderChunkAt} and {@code setDirty} map section Y into the
 *       window and return null / ignore outside it.</li>
 * </ul>
 *
 * When the configured height fits the window, the window base equals
 * {@code minSection}, the shift is zero, and behavior is identical to vanilla.
 */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin {

    @Unique
    private static final int RENDER_WINDOW_SECTIONS = 32;

    /**
     * The window re-centers only after the camera leaves this band around the
     * center, limiting the recompile burst to roughly one hysteresis step.
     */
    @Unique
    private static final int REBASE_HYSTERESIS_SECTIONS = 8;

    @Unique
    private static final int UNINITIALIZED = Integer.MIN_VALUE;

    @Shadow
    @Final
    protected Level level;

    @Shadow
    protected int chunkGridSizeX;

    @Shadow
    protected int chunkGridSizeY;

    @Shadow
    protected int chunkGridSizeZ;

    @Shadow
    @Final
    public ChunkRenderDispatcher.RenderChunk[] chunks;

    @Shadow
    protected abstract int getChunkIndex(int x, int y, int z);

    /** Absolute section Y of the first section covered by the render window. */
    @Unique
    private int endless$windowBaseSection = UNINITIALIZED;

    @Redirect(
        method = "setViewDistance",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionsCount()I")
    )
    private int endless$capRenderSections(Level level) {
        return Math.min(level.getSectionsCount(), RENDER_WINDOW_SECTIONS);
    }

    @Inject(method = "repositionCamera", at = @At("HEAD"))
    private void endless$trackCameraSection(double x, double z, CallbackInfo ci) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        int cameraSection = camera.getBlockPosition().getY() >> 4;
        int minSection = this.level.getMinSection();
        int maxSection = this.level.getMaxSection();

        if (chunkGridSizeY >= this.level.getSectionsCount()) {
            // Grid covers the whole height: keep vanilla alignment.
            endless$windowBaseSection = minSection;
            return;
        }

        if (endless$windowBaseSection != UNINITIALIZED) {
            int center = endless$windowBaseSection + (RENDER_WINDOW_SECTIONS / 2);
            if (Math.abs(cameraSection - center) <= REBASE_HYSTERESIS_SECTIONS) {
                return;
            }
        }

        endless$windowBaseSection = Math.max(minSection, Math.min(
            cameraSection - (RENDER_WINDOW_SECTIONS / 2),
            maxSection - RENDER_WINDOW_SECTIONS
        ));
    }

    @Unique
    private int endless$windowBaseRelative() {
        return endless$windowBaseSection == UNINITIALIZED
            ? 0
            : endless$windowBaseSection - this.level.getMinSection();
    }

    @Redirect(
        method = "repositionCamera",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I")
    )
    private int endless$shiftGridBase(Level level) {
        return level.getMinBuildHeight() + (endless$windowBaseRelative() << 4);
    }

    @Inject(method = "getRenderChunkAt", at = @At("HEAD"), cancellable = true)
    private void endless$getRenderChunkAt(BlockPos pos, CallbackInfoReturnable<ChunkRenderDispatcher.RenderChunk> cir) {
        int ySection = Mth.floorDiv(pos.getY() - this.level.getMinBuildHeight(), 16)
            - endless$windowBaseRelative();
        if (ySection < 0 || ySection >= chunkGridSizeY) {
            cir.setReturnValue(null);
            return;
        }
        int xSection = Mth.positiveModulo(Mth.floorDiv(pos.getX(), 16), chunkGridSizeX);
        int zSection = Mth.positiveModulo(Mth.floorDiv(pos.getZ(), 16), chunkGridSizeZ);
        cir.setReturnValue(this.chunks[this.getChunkIndex(xSection, ySection, zSection)]);
    }

    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void endless$setDirty(int x, int y, int z, boolean dirty, CallbackInfo ci) {
        ci.cancel();
        int ySection = y - this.level.getMinSection() - endless$windowBaseRelative();
        if (ySection < 0 || ySection >= chunkGridSizeY) {
            return;
        }
        int xSection = Math.floorMod(x, chunkGridSizeX);
        int zSection = Math.floorMod(z, chunkGridSizeZ);
        this.chunks[this.getChunkIndex(xSection, ySection, zSection)].setDirty(dirty);
    }
}
