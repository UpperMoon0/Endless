package com.nstut.endless.compat.create;

import net.minecraft.core.BlockPos;

/** Share the renderer's exact crack keys with replacement block-entity renderers. */
public interface DestructionPositionLookup {
    long endless$destructionKey(BlockPos pos);
}
