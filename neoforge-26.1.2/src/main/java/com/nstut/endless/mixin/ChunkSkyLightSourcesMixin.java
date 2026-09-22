package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keeps legacy-wide dense chunks inside ChunkSkyLightSources' encoded range. */
@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightSourcesMixin {
    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/SimpleBitStorage;<init>(II)V"
        ),
        index = 0
    )
    private int endless$reserveSkyLightGuardBits(int vanillaBits) {
        return EndlessHeights.skyLightStorageBits(vanillaBits);
    }
}
