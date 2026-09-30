package com.nstut.endless.compat.create;

import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Full-coordinate cache context matching Create's wrench and placement paths.
 * Create skips handler ticks outside a world, so cached context must not keep
 * that entire world alive after disconnect while waiting for another join.
 */
public record CreateEjectorCacheKey(WeakReference<Object> world, boolean wrench, BlockPos position) {
    public static CreateEjectorCacheKey of(Object world, HitResult hit, boolean wrench) {
        if (world == null || !(hit instanceof BlockHitResult blockHit)
            || hit.getType() == HitResult.Type.MISS) return null;
        BlockPos position = blockHit.getBlockPos();
        if (!wrench) position = position.relative(blockHit.getDirection());
        return new CreateEjectorCacheKey(new WeakReference<>(world), wrench, position.immutable());
    }

    public boolean sameContext(CreateEjectorCacheKey other) {
        Object liveWorld = world.get();
        return liveWorld != null && other != null && liveWorld == other.world.get()
            && wrench == other.wrench && position.equals(other.position);
    }
}
