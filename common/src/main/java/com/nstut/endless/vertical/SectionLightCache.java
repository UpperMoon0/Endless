package com.nstut.endless.vertical;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded full-coordinate cache. Solves never hold the cache monitor. */
public final class SectionLightCache<V> {
    public record Key(int x, int y, int z) {}
    private static final class Flight {
        long generation;
        int users;
    }
    private final int limit;
    private final Map<Key, V> values = new LinkedHashMap<>(64, .75f, true);
    // Only keys being solved need generations; disjoint writes never restart them.
    private final Map<Key, Flight> inFlight = new HashMap<>();

    public SectionLightCache(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive cache limit required");
        this.limit = limit;
    }

    public V get(Key key, Supplier<V> solve) {
        while (true) {
            Flight flight;
            long observed;
            synchronized (this) {
                V cached = values.get(key);
                if (cached != null) return cached;
                flight = inFlight.computeIfAbsent(key, ignored -> new Flight());
                flight.users++;
                observed = flight.generation;
            }
            V result;
            try {
                result = Objects.requireNonNull(solve.get());
            } catch (RuntimeException | Error error) {
                synchronized (this) { release(key, flight); }
                throw error;
            }
            synchronized (this) {
                boolean invalidated = observed != flight.generation;
                release(key, flight);
                if (invalidated) continue;
                V existing = values.get(key);
                if (existing != null) return existing;
                values.put(key, result);
                while (values.size() > limit) values.remove(values.keySet().iterator().next());
                return result;
            }
        }
    }

    private void release(Key key, Flight flight) {
        if (--flight.users == 0) inFlight.remove(key, flight);
    }

    public synchronized void invalidateBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int x0 = Math.floorDiv(minX, 16), y0 = Math.floorDiv(minY, 16), z0 = Math.floorDiv(minZ, 16);
        int x1 = Math.floorDiv(maxX, 16), y1 = Math.floorDiv(maxY, 16), z1 = Math.floorDiv(maxZ, 16);
        values.keySet().removeIf(k -> contains(k, x0, y0, z0, x1, y1, z1));
        inFlight.forEach((k, flight) -> {
            if (contains(k, x0, y0, z0, x1, y1, z1)) flight.generation++;
        });
    }

    private static boolean contains(Key k, int x0, int y0, int z0, int x1, int y1, int z1) {
        return k.x >= x0 && k.x <= x1 && k.y >= y0 && k.y <= y1 && k.z >= z0 && k.z <= z1;
    }

    public synchronized void clear() {
        values.clear();
        inFlight.values().forEach(flight -> flight.generation++);
    }

    public synchronized int size() { return values.size(); }
}
