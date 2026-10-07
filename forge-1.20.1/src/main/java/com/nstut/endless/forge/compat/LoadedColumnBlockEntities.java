package com.nstut.endless.forge.compat;

import java.util.function.Consumer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Loaded block entities whose origins have no terrain render section. */
public interface LoadedColumnBlockEntities {
    void endless$forEachLoadedBlockEntity(Consumer<BlockEntity> consumer);
}
