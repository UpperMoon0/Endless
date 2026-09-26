package com.nstut.endless.vertical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Lifecycle bridge for sparse POI sidecar data. */
public final class ExtendedPoiStorage {
    private ExtendedPoiStorage() {}

    public static void flush(ServerLevel level, ChunkPos chunkPos) {
        ((ExtendedSectionStorageAccess) (Object) level.getPoiManager())
            .endless$flushExtendedColumn(chunkPos);
    }

    public static void unload(ServerLevel level, ChunkPos chunkPos) {
        ((ExtendedSectionStorageAccess) (Object) level.getPoiManager())
            .endless$unloadExtendedColumn(chunkPos);
    }
}
