package com.nstut.endless.vertical;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Deduplicated round-robin work with per-owner limits and cancellable entries. */
public final class FairWorkQueue<O, K, V> {
    private final Map<O, LinkedHashMap<K, V>> owners = new LinkedHashMap<>();

    public void offer(O owner, K key, V value) {
        owners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    /** Preserve near-first ordering and refuse excess newly discovered work. */
    public boolean offerBounded(O owner, K key, V value, int maxPerOwner) {
        if (maxPerOwner < 1) throw new IllegalArgumentException("Positive owner limit required");
        var queue = owners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        if (!queue.containsKey(key) && queue.size() >= maxPerOwner) return false;
        queue.put(key, value);
        return true;
    }

    public int ownerSize(O owner) {
        var queue = owners.get(owner);
        return queue == null ? 0 : queue.size();
    }

    public void removeOwner(O owner) { owners.remove(owner); }

    public void removeIf(Predicate<V> obsolete) {
        owners.values().forEach(queue -> queue.values().removeIf(obsolete));
        owners.values().removeIf(Map::isEmpty);
    }

    public int drain(int total, int perOwner, Consumer<V> work) {
        return drainBudgeted(total, perOwner, Long.MAX_VALUE, Long.MAX_VALUE, value -> {
            work.accept(value);
            return 0;
        });
    }

    /** Time and byte budgets are checked after each indivisible operation.
     * An oversized single page may exceed the target once; no later page starts. */
    public int drainBudgeted(int total, int perOwner, long maxBytes, long maxNanos, ToLongFunction<V> work) {
        if (total < 1 || perOwner < 1 || maxBytes < 1 || maxNanos < 1) return 0;
        long started = System.nanoTime();
        long bytes = 0;
        int done = 0, visits = owners.size();
        while (done < total && visits-- > 0 && !owners.isEmpty()) {
            O owner = owners.keySet().iterator().next();
            var queue = owners.remove(owner);
            for (int n = 0; n < perOwner && done < total && !queue.isEmpty(); n++) {
                if (done > 0 && (bytes >= maxBytes || System.nanoTime() - started >= maxNanos)) break;
                K key = queue.keySet().iterator().next();
                V value = queue.remove(key);
                bytes += Math.max(0, work.applyAsLong(value));
                done++;
            }
            if (!queue.isEmpty()) owners.put(owner, queue);
            if (done > 0 && (bytes >= maxBytes || System.nanoTime() - started >= maxNanos)) break;
        }
        return done;
    }

    public void removeOwners(Predicate<O> test) { owners.keySet().removeIf(test); }
    public void clear() { owners.clear(); }
    public int size() { return owners.values().stream().mapToInt(Map::size).sum(); }
}
