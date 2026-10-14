package com.nstut.endless.mixin;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.core.BlockPos;
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
 * <p>Vanilla RenderChunk snapshots only {@code LevelChunkSection[]} and therefore
 * cannot contain Endless pages. The target class is package-private, so this
 * mixin uses a string target while shadowing its wrapped LevelChunk.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderChunk")
public abstract class RenderChunkMixin {
    @Shadow @Final private LevelChunk wrapped;
    @Unique private Int2ObjectMap<PalettedContainer<BlockState>> endless$sparseStates;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void endless$captureSparseStates(LevelChunk chunk, CallbackInfo ci) {
        if (com.nstut.endless.heights.EndlessLogicalHeights.isActive()) {
            endless$sparseStates = EndlessVerticalEngine.world(chunk.getLevel())
                .copyRenderBlockStates(chunk.getPos().x, chunk.getPos().z);
        }
    }


    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (EndlessVerticalEngine.isExtendedY(wrapped.getLevel(), pos.getY())) {
            LiveRenderProbe.recordRenderChunk(pos);
            if (endless$sparseStates == null) {
                cir.setReturnValue(EndlessVerticalEngine.world(wrapped.getLevel()).getBlockState(pos));
            } else {
                var states = endless$sparseStates.get(pos.getY() >> 4);
                cir.setReturnValue(states == null ? Blocks.AIR.defaultBlockState()
                    : states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
            }
        }
    }
}
