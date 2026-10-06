package com.nstut.endless.fabric.mixin.compat.embeddium;

import com.nstut.endless.fabric.compat.EmbeddiumSections;
import com.nstut.endless.fabric.compat.EmbeddiumWindowBounds;
import com.nstut.endless.fabric.compat.EmbeddiumSortCamera;
import com.nstut.endless.fabric.compat.EmbeddiumSnapshotInvalidation;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortTask;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.VerticalRenderWindow;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class RenderSectionManagerMixin implements EmbeddiumSnapshotInvalidation {
    @Shadow @Final private ClientLevel world;
    @Shadow @Final private ClonedChunkSectionCache sectionCache;
    @Shadow @Final private OcclusionCuller occlusionCuller;
    @Shadow public abstract void onSectionAdded(int x, int y, int z);
    @Shadow public abstract void onSectionRemoved(int x, int y, int z);
    @Shadow public abstract void scheduleRebuild(int x, int y, int z, boolean important);
    @Shadow private RenderSection getRenderSection(int x, int y, int z) { throw new AssertionError(); }
    @Shadow private void resetRenderLists() { throw new AssertionError(); }
    @Shadow private boolean needsUpdate;
    @Unique private final VerticalRenderWindow endless$window = new VerticalRenderWindow();
    @Unique private final LongSet endless$readyChunks = new LongOpenHashSet();

    @Unique private final com.nstut.endless.vertical.SkyColumnBatch endless$denseSky = new com.nstut.endless.vertical.SkyColumnBatch();
    @Override public void endless$queueDenseSkyColumns(int x, int z) {
        if (!EndlessLogicalHeights.isActive() || !world.dimensionType().hasSkyLight()) return;
        // Clear on the first notification and again at the render boundary,
        // covering point queries cached between successive edits in a batch.
        if (endless$denseSky.add(x, z)) com.nstut.endless.vertical.EndlessVerticalEngine.world(world).invalidateSkyLight();
    }
    @Override public int endless$flushDenseSkyColumns() {
        if (!endless$denseSky.isEmpty()) com.nstut.endless.vertical.EndlessVerticalEngine.world(world).invalidateSkyLight();
        return endless$denseSky.drain((x, z) -> endless$refreshSkyColumn(x, z, true));
    }
    @Override public void endless$invalidateSkyColumns(int chunkX, int chunkZ) {
        if (!EndlessLogicalHeights.isActive() || !world.dimensionType().hasSkyLight()) return;
        for (int x = chunkX - 1; x <= chunkX + 1; x++) for (int z = chunkZ - 1; z <= chunkZ + 1; z++)
            endless$refreshSkyColumn(x, z, false);
    }
    @Unique private void endless$refreshSkyColumn(int x, int z, boolean dense) {
        // Include WorldSlice's vertical snapshot halo, even without a render node.
        for (int y = endless$window.minSection() - 1; y <= endless$window.maxSection(); y++) {
            sectionCache.invalidate(x, y, z);
            if (endless$window.contains(y) && endless$readyChunks.contains(ChunkPos.asLong(x, z))) {
                if (dense) {
                    var node = getRenderSection(x, y, z);
                    var section = EmbeddiumSections.get(world, world.getChunk(x, z), y);
                    // Empty geometry has no light-dependent vertices. Preserve
                    // pending native edits and old geometry awaiting removal.
                    if (node == null || (node.getFlags() == 0 && node.getPendingUpdate() == null
                        && (section == null || section.hasOnlyAir()))) continue;
                }
                scheduleRebuild(x, y, z, false);
            }
        }
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"), cancellable = true)
    private void endless$addChunk(int x, int z, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) return;
        ci.cancel();
        endless$updateWindow();
        if (!endless$readyChunks.add(ChunkPos.asLong(x, z))) return;
        for (int y = endless$window.minSection(); y < endless$window.maxSection(); y++) {
            sectionCache.invalidate(x, y, z);
            onSectionAdded(x, y, z);
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"), cancellable = true)
    private void endless$removeChunk(int x, int z, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive()) return;
        ci.cancel();
        if (!endless$readyChunks.remove(ChunkPos.asLong(x, z))) return;
        for (int y = endless$window.minSection(); y < endless$window.maxSection(); y++) {
            onSectionRemoved(x, y, z);
            sectionCache.invalidate(x, y, z);
        }
    }

    @Inject(method = "updateChunks", at = @At("HEAD"))
    private void endless$followMainCamera(boolean immediately, CallbackInfo ci) {
        if (EndlessLogicalHeights.isActive()) {
            endless$updateWindow();
        }
    }

    @Unique
    private void endless$updateWindow() {
        int previousMin = endless$window.minSection();
        int previousMax = endless$window.maxSection();
        // Oculus invokes the manager again for shadows. Always follow the main
        // camera, never a shadow frustum, so both passes share one stable grid.
        int camera = Math.floorDiv(Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition().getY(), 16);
        if (!endless$window.update(camera, EndlessLogicalHeights.minSection(), EndlessLogicalHeights.maxSectionExclusive())) return;
        ((EmbeddiumWindowBounds) occlusionCuller).endless$setWindowBounds(endless$window.minSection(), endless$window.maxSection());
        for (long key : endless$readyChunks) {
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            for (int y = previousMin; y < previousMax; y++) {
                if (!endless$window.contains(y)) {
                    // Native removal cancels jobs, disposes the section and
                    // disconnects graph edges; native upload rejects disposed results.
                    onSectionRemoved(x, y, z);
                    sectionCache.invalidate(x, y, z);
                }
            }
            for (int y = endless$window.minSection(); y < endless$window.maxSection(); y++) {
                if (y < previousMin || y >= previousMax) {
                    sectionCache.invalidate(x, y, z);
                    onSectionAdded(x, y, z);
                }
            }
        }
        resetRenderLists();
        needsUpdate = true;
    }

    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;", remap = true))
    private LevelChunkSection[] endless$section(ChunkAccess chunk, int x, int y, int z) {
        return new LevelChunkSection[] { EmbeddiumSections.get(world, chunk, y) };
    }

    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getSectionIndexFromSectionY(I)I", remap = true))
    private int endless$sectionIndex(ClientLevel level, int y) { return 0; }
    @Redirect(method = "onSectionAdded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;hasOnlyAir()Z", remap = true))
    private boolean endless$emptySparseSection(LevelChunkSection section) { return section == null || section.hasOnlyAir(); }

}
