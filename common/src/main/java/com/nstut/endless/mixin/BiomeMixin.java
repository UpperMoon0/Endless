package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Extends biome temperature/precipitation semantics into sparse logical Y. */
@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Invoker("getHeightAdjustedTemperature")
    protected abstract float endless$getHeightAdjustedTemperature(BlockPos pos);

    /** BlockPos.asLong aliases above vanilla's 12-bit Y range; bypass that cache there. */
    @Inject(method = "getTemperature", at = @At("HEAD"), cancellable = true)
    private void endless$getTemperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (EndlessLogicalHeights.needsExtendedBlockPosEncoding(pos.getY())) {
            cir.setReturnValue(endless$getHeightAdjustedTemperature(pos));
        }
    }

    @Inject(
        method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z",
        at = @At("HEAD"), cancellable = true)
    private void endless$shouldFreeze(
        LevelReader level,
        BlockPos pos,
        boolean mustBeAtEdge,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!endless$isSparseLogical(pos.getY())) {
            return;
        }
        Biome self = (Biome) (Object) this;
        if (self.warmEnoughToRain(pos) || level.getBrightness(LightLayer.BLOCK, pos) >= 10) {
            cir.setReturnValue(false);
            return;
        }
        BlockState state = level.getBlockState(pos);
        FluidState fluid = level.getFluidState(pos);
        if (fluid.getType() != Fluids.WATER || !(state.getBlock() instanceof LiquidBlock)) {
            cir.setReturnValue(false);
            return;
        }
        if (!mustBeAtEdge) {
            cir.setReturnValue(true);
            return;
        }
        boolean surrounded = level.isWaterAt(pos.west())
            && level.isWaterAt(pos.east())
            && level.isWaterAt(pos.north())
            && level.isWaterAt(pos.south());
        cir.setReturnValue(!surrounded);
    }

    @Inject(
        method = "shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"), cancellable = true)
    private void endless$shouldSnow(
        LevelReader level,
        BlockPos pos,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!endless$isSparseLogical(pos.getY())) {
            return;
        }
        Biome self = (Biome) (Object) this;
        if (self.warmEnoughToRain(pos) || level.getBrightness(LightLayer.BLOCK, pos) >= 10) {
            cir.setReturnValue(false);
            return;
        }
        BlockState state = level.getBlockState(pos);
        cir.setReturnValue((state.isAir() || state.is(Blocks.SNOW))
            && Blocks.SNOW.defaultBlockState().canSurvive(level, pos));
    }

    private static boolean endless$isSparseLogical(int y) {
        return EndlessLogicalHeights.isActive()
            && EndlessLogicalHeights.isSparseBuildHeight(y);
    }
}
