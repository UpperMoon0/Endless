package com.nstut.endless.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void onSetBlockState(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        int idx = self.getSectionIndex(pos.getY());
        if (idx < 0 || idx >= self.getSections().length) {
            cir.setReturnValue(null);
        }
    }
}
