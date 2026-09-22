package com.nstut.endless.vertical;

import net.minecraft.world.level.ChunkPos;

import java.nio.file.Path;
import java.util.List;

/** Internal bridge exposing sparse sections owned by a SectionStorage mixin. */
public interface ExtendedSectionStorageAccess {
    void endless$setPoiRoot(Path root);
    List<?> endless$getExtendedSections(ChunkPos chunkPos);
    void endless$flushExtendedColumn(ChunkPos chunkPos);
    void endless$unloadExtendedColumn(ChunkPos chunkPos);
}
