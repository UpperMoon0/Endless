package com.nstut.endless.vertical;

import net.minecraft.world.level.ChunkPos;

import java.util.List;

/** Internal bridge exposing only sparse sections owned by a SectionStorage mixin. */
public interface ExtendedSectionStorageAccess {
    List<?> endless$getExtendedSections(ChunkPos chunkPos);
}
