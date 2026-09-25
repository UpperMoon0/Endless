package com.nstut.endless.mixin;

import com.nstut.endless.testing.LiveRenderProbe;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 26.1 replaced RenderChunk with an immutable per-section SectionCopy.
 * Sparse sections have no dense PalettedContainer snapshot, so route their
 * reads back through Endless' sparse world instead.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionCopy")
public abstract class RenderChunkMixin {
    @Shadow @Final private LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (this.levelHeightAccessor instanceof LevelChunk chunk
            && EndlessVerticalEngine.isExtendedY(chunk.getLevel(), pos.getY())) {
            LiveRenderProbe.recordRenderChunk(pos);
            cir.setReturnValue(EndlessVerticalEngine.world(chunk.getLevel()).getBlockState(pos));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (this.levelHeightAccessor instanceof LevelChunk chunk
            && EndlessVerticalEngine.isExtendedY(chunk.getLevel(), pos.getY())) {
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            LiveRenderProbe.recordRenderChunkBlockEntity(pos, blockEntity);
            cir.setReturnValue(blockEntity);
        }
    }
}
