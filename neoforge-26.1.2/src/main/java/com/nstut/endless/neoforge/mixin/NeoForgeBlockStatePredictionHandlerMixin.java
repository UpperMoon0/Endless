package com.nstut.endless.neoforge.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge assumes retainKnownServerState always inserted into vanilla's
 * packed-long ledger before retainSnapshot. Endless keeps extended-Y entries
 * in an object-keyed ledger instead, so the NeoForge snapshot write must not
 * dereference the absent packed entry.
 */
@Mixin(BlockStatePredictionHandler.class)
public abstract class NeoForgeBlockStatePredictionHandlerMixin {
    @Inject(method = "retainSnapshot", at = @At("HEAD"), cancellable = true, remap = false)
    private void endless$skipPackedSnapshotAtExtendedY(
        BlockPos pos,
        BlockSnapshot snapshot,
        CallbackInfo ci
    ) {
        if (EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())) {
            ci.cancel();
        }
    }
}
