package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.prediction.ExtendedPredictedState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Extends client block-state prediction beyond vanilla's packed BlockPos Y range.
 *
 * <p>Vanilla keys prediction state by {@code BlockPos.asLong()}, whose Y field is
 * only 12 bits. High-Y interactions would therefore alias another vertical
 * position and later reconstruct the wrong BlockPos. Endless keeps those entries
 * in an object-keyed side map while leaving normal prediction byte-for-byte
 * vanilla.</p>
 */
@Mixin(BlockStatePredictionHandler.class)
public abstract class BlockStatePredictionHandlerMixin {
    @Shadow private int currentSequenceNr;

    @Unique
    private final Map<BlockPos, ExtendedPredictedState> endless$extendedStates = new HashMap<>();

    @Inject(method = "retainKnownServerState", at = @At("HEAD"), cancellable = true)
    private void endless$retainKnownServerState(
        BlockPos pos,
        BlockState state,
        LocalPlayer player,
        CallbackInfo ci
    ) {
        if (!EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())) {
            return;
        }
        ci.cancel();
        BlockPos key = pos.immutable();
        ExtendedPredictedState previous = endless$extendedStates.get(key);
        if (previous != null) {
            previous.updateSequence(currentSequenceNr);
        } else {
            endless$extendedStates.put(key,
                new ExtendedPredictedState(currentSequenceNr, state, player.position()));
        }
    }

    @Inject(method = "updateKnownServerState", at = @At("HEAD"), cancellable = true)
    private void endless$updateKnownServerState(
        BlockPos pos,
        BlockState state,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())) {
            return;
        }
        ExtendedPredictedState predicted = endless$extendedStates.get(pos);
        if (predicted == null) {
            cir.setReturnValue(false);
            return;
        }
        predicted.updateState(state);
        cir.setReturnValue(true);
    }

    @Inject(method = "endPredictionsUpTo", at = @At("TAIL"))
    private void endless$endPredictionsUpTo(int sequence, ClientLevel level, CallbackInfo ci) {
        Iterator<Map.Entry<BlockPos, ExtendedPredictedState>> iterator =
            endless$extendedStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, ExtendedPredictedState> entry = iterator.next();
            ExtendedPredictedState predicted = entry.getValue();
            if (predicted.sequence() > sequence) {
                continue;
            }
            BlockPos pos = entry.getKey();
            iterator.remove();
            level.syncBlockState(pos, predicted.state(), predicted.playerPos());
        }
    }
}
