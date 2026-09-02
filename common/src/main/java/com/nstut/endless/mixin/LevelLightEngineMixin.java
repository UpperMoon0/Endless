package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessLayerLightEventListener;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps high-Y light queries out of vanilla packed BlockPos propagation. */
@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;

    @Inject(method = "getLayerListener", at = @At("RETURN"), cancellable = true)
    private void endless$wrapLayerListener(
        LightLayer layer,
        CallbackInfoReturnable<LayerLightEventListener> cir
    ) {
        if (!EndlessLogicalHeights.isActive() || !(levelHeightAccessor instanceof Level level)) {
            return;
        }
        LayerLightEventListener delegate = cir.getReturnValue();
        if (!(delegate instanceof EndlessLayerLightEventListener)) {
            cir.setReturnValue(new EndlessLayerLightEventListener(level, layer, delegate));
        }
    }

    @Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
    private void endless$getRawBrightness(BlockPos pos, int skyDarken, CallbackInfoReturnable<Integer> cir) {
        if (!(levelHeightAccessor instanceof Level level)
            || !EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            return;
        }
        int sky = EndlessVerticalEngine.world(level).getBrightness(LightLayer.SKY, pos) - skyDarken;
        int block = EndlessVerticalEngine.world(level).getBrightness(LightLayer.BLOCK, pos);
        cir.setReturnValue(Math.max(block, sky));
    }
}
