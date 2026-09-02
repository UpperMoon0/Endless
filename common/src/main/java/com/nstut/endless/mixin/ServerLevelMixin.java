package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.SparseRandomTicker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds random block/fluid ticking for loaded sparse sections after the dense chunk tick. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "tickChunk", at = @At("TAIL"))
    private void endless$tickSparseSections(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (EndlessLogicalHeights.isActive()) {
            SparseRandomTicker.tickLoadedColumn((ServerLevel) (Object) this, chunk, randomTickSpeed);
        }
    }
}
