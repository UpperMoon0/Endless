package com.nstut.endless.vertical;

import java.io.IOException;
import java.util.Optional;

/**
 * Persistence boundary for sparse vertical pages.
 *
 * <p>The first engine slice intentionally defines this boundary before wiring
 * it to vanilla region files. Extended pages must never be silently serialized
 * through vanilla {@code ChunkSerializer}, whose section-Y format and dense
 * column assumptions are not suitable for arbitrary Y.</p>
 */
public interface VerticalPagePersistence<S> {

    Optional<VerticalPage<S>> load(VerticalPagePos pos) throws IOException;

    void save(VerticalPagePos pos, VerticalPage<S> page) throws IOException;

    void delete(VerticalPagePos pos) throws IOException;
}
