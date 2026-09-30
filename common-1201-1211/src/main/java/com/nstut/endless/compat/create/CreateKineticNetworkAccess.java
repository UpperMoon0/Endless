package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Optional kinetic identity access without a hard Create dependency. */
public interface CreateKineticNetworkAccess {
    Long endless$getKineticNetworkId();
    BlockPos endless$getKineticSource();
    boolean endless$hasLegacyIdentity();
    void endless$prepareKineticRoot();
    boolean endless$acceptResolvedNetwork(Long id);
    void endless$readLegacyMarker(CompoundTag tag);
    void endless$writeLegacyMarker(CompoundTag tag);
}
