package com.nstut.endless.vertical;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Tracks only admitted sparse sections, without walking vanilla's global storage. */
public final class SparseSectionIndex {
    private final Map<Long, Set<Long>> columns = new HashMap<>();

    public void add(long column, long section) {
        columns.computeIfAbsent(column, ignored -> new HashSet<>()).add(section);
    }

    public Set<Long> sections(long column) {
        Set<Long> found = columns.get(column);
        return found == null ? Set.of() : Set.copyOf(found);
    }

    public Set<Long> remove(long column) {
        Set<Long> found = columns.remove(column);
        return found == null ? Set.of() : found;
    }
}
