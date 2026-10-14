package com.nstut.endless.mixin;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.Blocks;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Minecraft's immutable render-chunk facade read sparse states at high Y.
 *
 * <p>Vanilla RenderChunk copies only dense block palettes and therefore
 * cannot contain Endless pages. The target class is package-private, so this
 * mixin uses a string target while shadowing its wrapped LevelChunk.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderChunk")
public abstract class RenderChunkMixin {
    @Shadow @Final private LevelChunk wrapped;
    @Unique private Int2ObjectMap<PalettedContainer<BlockState>> endless$sparseStates;
    @Unique private int endless$firstSnapshotSection;
    @Unique private int endless$endSnapshotSection;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void endless$captureSparseStates(LevelChunk chunk, CallbackInfo ci) {
        if (com.nstut.endless.heights.EndlessLogicalHeights.isActive()) {
            // The 32-section window has 8-section rebase hysteresis. A 65-section
            // camera vicinity includes it and its meshing halo without copying
            // historical pages retained after vertical travel.
            int cameraSection = Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition().getY() >> 4;
            endless$firstSnapshotSection = cameraSection - 32;
            endless$endSnapshotSection = cameraSection + 33;
            endless$sparseStates = EndlessVerticalEngine.world(chunk.getLevel())
                .copyRenderBlockStates(chunk.getPos().x, chunk.getPos().z,
                    endless$firstSnapshotSection, endless$endSnapshotSection);
        }
    }


    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (EndlessVerticalEngine.isExtendedY(wrapped.getLevel(), pos.getY())) {
            LiveRenderProbe.recordRenderChunk(pos);
            if (endless$sparseStates == null || (pos.getY() >> 4) < endless$firstSnapshotSection
                || (pos.getY() >> 4) >= endless$endSnapshotSection) {
                cir.setReturnValue(EndlessVerticalEngine.world(wrapped.getLevel()).getBlockState(pos));
            } else {
                var states = endless$sparseStates.get(pos.getY() >> 4);
                cir.setReturnValue(states == null ? Blocks.AIR.defaultBlockState()
                    : states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
            }
        }
    }

    /**
     * Vanilla RenderChunk freezes LevelChunk#getBlockEntities() at construction.
     * Sparse block entities can be registered just after that snapshot on join,
     * leaving a render compilation with the right block state but a stale null
     * block entity until another block update rebuilds the section. Extended-Y
     * block entities must read the wrapped client chunk live, independently of the
     * immutable block-state palettes used by mesh workers.
     */
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (EndlessVerticalEngine.isExtendedY(wrapped.getLevel(), pos.getY())) {
            BlockEntity blockEntity = wrapped.getBlockEntity(pos);
            LiveRenderProbe.recordRenderChunkBlockEntity(pos, blockEntity);
            cir.setReturnValue(blockEntity);
        }
    }
}
