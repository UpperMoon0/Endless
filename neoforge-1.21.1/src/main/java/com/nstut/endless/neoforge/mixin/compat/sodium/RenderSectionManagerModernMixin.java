package com.nstut.endless.neoforge.mixin.compat.sodium;

import com.nstut.endless.compat.EmbeddiumSections;
import com.nstut.endless.neoforge.compat.EmbeddiumWindowBounds;
import com.nstut.endless.neoforge.compat.EmbeddiumSnapshotInvalidation;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.compat.RendererSectionState;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class RenderSectionManagerModernMixin implements EmbeddiumSnapshotInvalidation, RendererSectionState.NativeSections {
    @Shadow @Final private ClientLevel level;
    @Shadow @Final private ClonedChunkSectionCache sectionCache;
    @Shadow @Final private OcclusionCuller occlusionCuller;
    @Shadow public abstract void onSectionAdded(int x, int y, int z);
    @Shadow public abstract void onSectionRemoved(int x, int y, int z);
    @Shadow public abstract void scheduleRebuild(int x, int y, int z, boolean important);
    @Shadow private RenderSection getRenderSection(int x, int y, int z) { throw new AssertionError(); }
    @Shadow private void resetRenderLists() { throw new AssertionError(); }
    @Shadow private boolean needsGraphUpdate;
    @Unique private final RendererSectionState endless$sections = new RendererSectionState(this);

    @Override public void endless$queueDenseSkyColumns(int x, int z) {
        if (EndlessLogicalHeights.isActive() && level.dimensionType().hasSkyLight()) endless$sections.queueDenseSky(x, z);
    }
    @Override public int endless$flushDenseSkyColumns() { return endless$sections.flushDenseSky(); }
    @Override public void endless$invalidateSkyColumns(int x, int z) {
        if (EndlessLogicalHeights.isActive() && level.dimensionType().hasSkyLight()) endless$sections.invalidateSkyColumns(x, z);
    }

    @Override public void endless$invalidateSnapshot(int x, int y, int z) { sectionCache.invalidate(x, y, z); }
    @Override public void endless$addSection(int x, int y, int z) { onSectionAdded(x, y, z); }
    @Override public void endless$removeSection(int x, int y, int z) { onSectionRemoved(x, y, z); }
    @Override public boolean endless$rebuildSection(int x, int y, int z) {
        var node = getRenderSection(x, y, z);
        if (node == null || node.isDisposed()) return true;
        // Native scheduleRebuild ignores unbuilt nodes. Keep a notification
        // until the first build uploads, then rebuild from the fresh snapshot.
        if (!node.isBuilt()) return false;
        scheduleRebuild(x, y, z, false);
        return true;
    }
    @Inject(method = "scheduleRebuild", at = @At("RETURN"))
    private void endless$retainInitialBuildEdits(int x, int y, int z, boolean important, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) return;
        var node = getRenderSection(x, y, z);
        if (node != null && !node.isBuilt() && !node.isDisposed()) endless$sections.deferRebuild(x, y, z);
    }
    @Override public void endless$setBounds(int min, int max) {
        ((EmbeddiumWindowBounds) occlusionCuller).endless$setWindowBounds(min, max);
    }
    @Override public void endless$resetGraph() { resetRenderLists(); needsGraphUpdate = true; }
    @Override public void endless$invalidatePointLight() {
        com.nstut.endless.vertical.EndlessVerticalEngine.world(level).invalidateSkyLight();
    }
    @Override public boolean endless$needsDenseRebuild(int x, int y, int z) {
        var node = getRenderSection(x, y, z);
        if (node == null) return false;
        var section = EmbeddiumSections.get(level, level.getChunk(x, z), y);
        // Keep old geometry awaiting removal and pending native edits.
        return !(node.getFlags() == 0 && !((com.nstut.endless.neoforge.compat.SodiumPendingUpdate) node).endless$hasPendingUpdate()
            && (section == null || section.hasOnlyAir()));
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"), cancellable = true)
    private void endless$addChunk(int x, int z, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) return;
        ci.cancel();
        endless$updateWindow();
        endless$sections.addChunk(x, z);
    }
    @Inject(method = "onChunkRemoved", at = @At("HEAD"), cancellable = true)
    private void endless$removeChunk(int x, int z, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) return;
        ci.cancel();
        endless$sections.removeChunk(x, z);
    }
    @Inject(method = "updateChunks", at = @At("HEAD"))
    private void endless$followMainCamera(boolean immediately, CallbackInfo ci) {
        if (EndlessLogicalHeights.isActive()) endless$updateWindow();
    }
    @Unique private void endless$updateWindow() {
        // Main camera only: shadow passes share the same terrain window.
        int camera = Math.floorDiv(Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition().getY(), 16);
        endless$sections.updateWindow(camera, EndlessLogicalHeights.minSection(), EndlessLogicalHeights.maxSectionExclusive());
    }

    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;", remap = true))
    private LevelChunkSection[] endless$section(ChunkAccess chunk, int x, int y, int z) {
        return new LevelChunkSection[] { EmbeddiumSections.get(level, chunk, y) };
    }

    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSectionIndexFromSectionY(I)I", remap = true))
    private int endless$sectionIndex(ClientLevel level, int y) { return 0; }
    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z", remap = true))
    private boolean endless$emptySparseSection(LevelChunkSection section) {
        return section == null || section.hasOnlyAir();
    }

}
