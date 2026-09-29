package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Full-coordinate cache context matching Create's wrench and placement paths. */
public record CreateEjectorCacheKey(Object world, boolean wrench, BlockPos position) {
    public static CreateEjectorCacheKey of(Object world, HitResult hit, boolean wrench) {
        if (world == null || !(hit instanceof BlockHitResult blockHit)
            || hit.getType() == HitResult.Type.MISS) return null;
        BlockPos position = blockHit.getBlockPos();
        if (!wrench) position = position.relative(blockHit.getDirection());
        return new CreateEjectorCacheKey(world, wrench, position.immutable());
    }

    public boolean sameContext(CreateEjectorCacheKey other) {
        return other != null && world == other.world && wrench == other.wrench
            && position.equals(other.position);
    }
}
