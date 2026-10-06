package com.nstut.endless.neoforge.compat;

/** A distant page can change sky exposure in the currently visible window. */
public interface EmbeddiumSnapshotInvalidation {
    void endless$queueDenseSkyColumns(int chunkX, int chunkZ);
    int endless$flushDenseSkyColumns();
    void endless$invalidateSkyColumns(int chunkX, int chunkZ);
}
