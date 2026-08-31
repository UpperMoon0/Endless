package com.nstut.endless.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DataLayerStorageMap.class)
public abstract class DataLayerStorageMapMixin {

    @Redirect(
        method = "getLayer",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;get(J)Ljava/lang/Object;"),
        require = 0
    )
    private Object redirectGetLayerGet(Long2ObjectOpenHashMap<DataLayer> map, long key) {
        DataLayer layer = map.get(key);
        return layer != null ? layer : new DataLayer();
    }

    @Redirect(
        method = "copyDataLayer",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;get(J)Ljava/lang/Object;"),
        require = 0
    )
    private Object redirectCopyDataLayerGet(Long2ObjectOpenHashMap<DataLayer> map, long key) {
        DataLayer layer = map.get(key);
        return layer != null ? layer : new DataLayer();
    }
}
