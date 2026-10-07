package com.nstut.endless.forge.mixin.compat.embeddium;

import com.nstut.endless.compat.create.DestructionPositionLookup;
import com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation;
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
    @Shadow private me.jellysquid.mods.sodium.client.render.viewport.Viewport currentViewport;
    @Shadow private static void renderBlockEntity(com.mojang.blaze3d.vertex.PoseStack poses, net.minecraft.client.renderer.RenderBuffers buffers,
        it.unimi.dsi.fastutil.longs.Long2ObjectMap<java.util.SortedSet<net.minecraft.server.level.BlockDestructionProgress>> destruction,
        float tick, net.minecraft.client.renderer.MultiBufferSource.BufferSource source, double x, double y, double z,
        net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher, net.minecraft.world.level.block.entity.BlockEntity entity) { throw new AssertionError(); }

    @Inject(method = "renderGlobalBlockEntities", at = @At("RETURN"))
    private void endless$renderMissingGlobals(com.mojang.blaze3d.vertex.PoseStack poses, net.minecraft.client.renderer.RenderBuffers buffers,
        it.unimi.dsi.fastutil.longs.Long2ObjectMap<java.util.SortedSet<net.minecraft.server.level.BlockDestructionProgress>> destruction,
        float tick, net.minecraft.client.renderer.MultiBufferSource.BufferSource source, double x, double y, double z,
        net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive() || renderSectionManager == null || currentViewport == null) return;
        // Compiled native globals already used the normal path above. Include
        // uncompiled origins inside the window as well as outside origins.
        var nativeGlobals = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<net.minecraft.world.level.block.entity.BlockEntity, Boolean>());
        for (var section : renderSectionManager.getSectionsWithGlobalEntities()) {
            var entities = section.getGlobalBlockEntities();
            if (entities != null) java.util.Collections.addAll(nativeGlobals, entities);
        }
        ((com.nstut.endless.forge.compat.LoadedColumnBlockEntities) renderSectionManager).endless$forEachLoadedBlockEntity(entity -> {
            if (nativeGlobals.contains(entity)) return;
            var renderer = dispatcher.getRenderer(entity);
            if (renderer == null || !renderer.shouldRenderOffScreen(entity) || !currentViewport.isBoxVisible(entity.getRenderBoundingBox())) return;
            renderBlockEntity(poses, buffers, destruction, tick, source, x, y, z, dispatcher, entity);
        });
    }

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
        long renderFrame = com.nstut.endless.forge.compat.EmbeddiumFrameClock.frame();
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
