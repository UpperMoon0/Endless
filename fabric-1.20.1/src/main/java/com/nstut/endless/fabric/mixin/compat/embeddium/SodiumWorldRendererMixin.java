package com.nstut.endless.fabric.mixin.compat.embeddium;

import com.nstut.endless.compat.create.DestructionPositionLookup;
import com.nstut.endless.fabric.compat.EmbeddiumSnapshotInvalidation;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererMixin implements EmbeddiumSnapshotInvalidation {
    @Shadow private RenderSectionManager renderSectionManager;

    @Override public void endless$invalidateSkyColumns(int x, int z) {
        if (renderSectionManager != null) ((EmbeddiumSnapshotInvalidation) renderSectionManager).endless$invalidateSkyColumns(x, z);
    }
    @Override public void endless$queueDenseSkyColumns(int x, int z) {
        if (renderSectionManager != null) ((EmbeddiumSnapshotInvalidation) renderSectionManager).endless$queueDenseSkyColumns(x, z);
    }
    @Override public int endless$flushDenseSkyColumns() {
        return renderSectionManager == null ? 0 : ((EmbeddiumSnapshotInvalidation) renderSectionManager).endless$flushDenseSkyColumns();
    }
    @Unique private RenderSectionManager endless$lastSkyManager;
    @Unique private long endless$lastSkyFrame;
    @Inject(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager;updateChunks(Z)V"))
    private void endless$flushSkyFrame(net.minecraft.client.Camera camera,
        me.jellysquid.mods.sodium.client.render.viewport.Viewport viewport, int frame,
        boolean spectator, boolean immediately, CallbackInfo ci) {
        // Embeddium increments its supplied counter per terrain pass, so use
        // Forge's outer render frame. Retain notifications
        // arriving after the first pass for the next frame, rather than repeat work.
        long renderFrame = com.nstut.endless.fabric.compat.EmbeddiumFrameClock.frame();
        if (renderSectionManager != endless$lastSkyManager || renderFrame != endless$lastSkyFrame) {
            endless$lastSkyManager = renderSectionManager;
            endless$lastSkyFrame = renderFrame;
            endless$flushDenseSkyColumns();
        }
    }
    @Inject(method = "scheduleRebuildForChunk", at = @At("RETURN"))
    private void endless$denseSkyUpdate(int x, int y, int z, boolean important, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null || !EndlessLogicalHeights.isActive() || !level.dimensionType().hasSkyLight()
            || EndlessVerticalEngine.isExtendedY(level, y << 4)) return;
        // Dense block packets and light-engine notifications both reach this
        // renderer entry point, including updates outside the camera window.
        Minecraft mc = Minecraft.getInstance();
        Runnable invalidate = () -> {
            if (mc.level != level) return;
            endless$queueDenseSkyColumns(x, z);
        };
        // Embeddium accepts off-thread dirty notifications; its snapshot cache
        // and our window/ready set must still be changed on the render thread.
        if (mc.isSameThread()) invalidate.run();
        else mc.execute(invalidate);
    }

    @Redirect(method = "renderBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J", remap = true))
    private static long endless$destructionKey(BlockPos pos) {
        return ((DestructionPositionLookup) Minecraft.getInstance().levelRenderer).endless$destructionKey(pos);
    }
}
