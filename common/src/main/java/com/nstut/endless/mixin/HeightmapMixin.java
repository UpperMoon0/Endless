package com.nstut.endless.mixin;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Heightmap.class)
public abstract class HeightmapMixin {

    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;ceillog2(I)I")
    )
    private int redirectCeilLog2(int vanillaHeight, ChunkAccess chunk) {
        int configuredHeight = chunk.getMaxBuildHeight() - chunk.getMinBuildHeight();
        return Mth.ceillog2(Math.max(vanillaHeight, configuredHeight + 1));
    }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void onUpdate(int x, int y, int z, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        int configuredHeight = EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight()
                             - EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
        int bits = Mth.ceillog2(configuredHeight + 1);
        long maxStoredValue = (1L << bits) - 1;
        int minY = EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
        long storedValue = (long) y - (long) minY;

        if (storedValue < 0 || storedValue > maxStoredValue) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
