package com.nstut.endless.mixin;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (EndlessVerticalEngine.isExtendedY(wrapped.getLevel(), pos.getY())) {
            cir.setReturnValue(EndlessVerticalEngine.world(wrapped.getLevel()).getBlockState(pos));
        }
    }
}
