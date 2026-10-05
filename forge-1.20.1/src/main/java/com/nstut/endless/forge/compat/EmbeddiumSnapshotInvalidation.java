package com.nstut.endless.forge.compat;

/** A distant page can change sky exposure in the currently visible window. */
public interface EmbeddiumSnapshotInvalidation {
    void endless$invalidateSkyColumns(int chunkX, int chunkZ);
}
