package com.nstut.endless.forge.compat;

import java.util.function.Consumer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Lifecycle tracking and indexed global-renderer traversal; never scans chunks during drawing. */
public interface LoadedColumnBlockEntities {
    void endless$forEachGlobalBlockEntity(Consumer<BlockEntity> consumer);
    void endless$trackBlockEntity(BlockEntity entity);
    void endless$invalidateGlobalRenderers();
}
