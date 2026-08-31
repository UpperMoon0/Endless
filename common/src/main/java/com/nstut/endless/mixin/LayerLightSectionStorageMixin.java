package com.nstut.endless.mixin;

import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {

    @Shadow
    protected abstract DataLayer getDataLayer(long key, boolean createIfAbsent);

    @Redirect(
        method = "setStoredLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/DataLayerStorageMap;copyDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;")
    )
    private DataLayer redirectCopyDataLayer(DataLayerStorageMap map, long key) {
        return getDataLayer(key, true);
    }

    @ModifyVariable(
        method = "getStoredLevel",
        at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;getDataLayer(JZ)Lnet/minecraft/world/level/chunk/DataLayer;"),
        argsOnly = false
    )
    private DataLayer guardGetStoredLevelLayer(DataLayer layer) {
        if (layer == null) {
            return new DataLayer();
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
            return new DataLayer();
        }
        return layer;
    }
}
