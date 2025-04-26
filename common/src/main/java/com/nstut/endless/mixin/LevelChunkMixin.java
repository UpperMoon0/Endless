package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents heightmap updates for blocks placed at extreme heights.
 * This is part of the solution to enable building beyond vanilla height limits.
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin {
    
    /**
     * Intercept the block state setting to conditionally skip heightmap updates
     * when blocks are placed at extreme heights (greater than 511 or less than 0).
     * 
     * The injection point is right after the block state is updated but before
     * any heightmap operations happen.
     */
    @Inject(
        method = "setBlockState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void onSetBlockState(BlockPos pos, BlockState state, boolean moved, 
                                CallbackInfoReturnable<BlockState> cir) {
        // If build height limits are removed, we need to handle blocks outside vanilla height range
        if (EndlessConfig.getInstance().getBuildHeight().isRemoveBuildHeightLimit()) {
            // Get the Y position
            int y = pos.getY();
            
            // If outside the safe range for heightmaps (0-511), we need to handle this specially
            if (y < 0 || y > 511) {
                // Get the block state that was just set
                LevelChunk chunk = (LevelChunk)(Object)this;
                BlockState oldState = cir.getReturnValue();
                
                // Skip heightmap updates but still handle other necessary logic
                // like light updates, block entities, etc.
                
                // Light updates should still happen
                if (oldState != null) {
                    chunk.getLevel().getChunkSource().getLightEngine().checkBlock(pos);
                }
                
                // Handle block entities if needed
                if (state.hasBlockEntity()) {
                    chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                }
                
                // Mark chunk as unsaved - using setUnsaved() method
                chunk.setUnsaved(true);
                
                // Return the old state to indicate the change occurred
                cir.setReturnValue(oldState);
                cir.cancel();
            }
        }
    }
}
