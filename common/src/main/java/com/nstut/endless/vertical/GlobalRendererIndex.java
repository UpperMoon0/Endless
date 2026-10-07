package com.nstut.endless.vertical;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Identity-based lifecycle index. Steady render passes visit candidates only. */
public final class GlobalRendererIndex<T> {
    private record Section(long column, int y) {}
    private final IdentityHashMap<T, Section> locations = new IdentityHashMap<>();
    private final Map<Long, Map<Integer, Set<T>>> columns = new HashMap<>();
    private final Set<T> dirty = identities();
    private final Set<T> candidates = identities();

    private static <T> Set<T> identities() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public void track(T entity, long column, int sectionY) {
        Section location = new Section(column, sectionY);
        Section old = locations.get(entity);
        if (old != null && !old.equals(location)) remove(entity);
        locations.put(entity, location);
        columns.computeIfAbsent(column, key -> new HashMap<>())
            .computeIfAbsent(sectionY, key -> identities()).add(entity);
        dirty.add(entity);
    }

    public void remove(T entity) {
        Section old = locations.remove(entity);
        dirty.remove(entity);
        candidates.remove(entity);
        if (old == null) return;
        var sections = columns.get(old.column);
        var entities = sections.get(old.y);
        entities.remove(entity);
        if (entities.isEmpty()) sections.remove(old.y);
        if (sections.isEmpty()) columns.remove(old.column);
    }

    public void removeColumn(long column) {
        var sections = columns.remove(column);
        if (sections == null) return;
        for (var entities : sections.values()) for (T entity : entities) {
            locations.remove(entity);
            dirty.remove(entity);
            candidates.remove(entity);
        }
    }

    public void dirtySection(long column, int y) {
        var sections = columns.get(column);
        if (sections != null && sections.containsKey(y)) dirty.addAll(sections.get(y));
    }

    /** Renderer resource reload is an explicit, infrequent full reclassification. */
    public void dirtyAll() { dirty.addAll(locations.keySet()); }

    public void refresh(Predicate<T> global) {
        if (dirty.isEmpty()) return;
        // Snapshot only changed identities; callbacks can enqueue subsequent work.
        var pending = new ArrayList<>(dirty);
        dirty.clear();
        for (T entity : pending) {
            if (!locations.containsKey(entity)) continue;
            if (global.test(entity)) candidates.add(entity);
            else candidates.remove(entity);
        }
    }

    public void forEachCandidate(Consumer<T> consumer) {
        candidates.forEach(consumer);
    }
}
