package com.nstut.endless.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {

    @ModifyVariable(
        method = "getStoredLevel",
        at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;getDataLayer(JZ)Lnet/minecraft/world/level/chunk/DataLayer;"),
        argsOnly = false
    )
    private DataLayer guardGetStoredLevelLayer(DataLayer layer) {
        if (layer == null) {
            return new DataLayer(2048);
        }
        return layer;
    }

    @ModifyVariable(
        method = "setStoredLevel",
        at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;getDataLayer(JZ)Lnet/minecraft/world/level/chunk/DataLayer;"),
        argsOnly = false
    )
    private DataLayer guardSetStoredLevelLayer(DataLayer layer) {
        if (layer == null) {
            return new DataLayer(2048);
        }
        return layer;
    }
}
