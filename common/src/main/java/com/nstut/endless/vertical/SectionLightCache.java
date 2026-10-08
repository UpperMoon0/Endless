package com.nstut.endless.vertical;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded, full-coordinate cache. Solvers run without holding the cache monitor. */
public final class SectionLightCache<V> {
    public record Key(int x, int y, int z) {}
    private final int limit;
    private final Map<Key,V> values = new LinkedHashMap<>(64,.75f,true);
    private long epoch;
    public SectionLightCache(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Positive cache limit required");
        this.limit=limit;
    }
    public V get(Key key, Supplier<V> solve) {
        while (true) {
            long observed;
            synchronized (this) {
                V cached=values.get(key);
                if (cached!=null) return cached;
                observed=epoch;
            }
            V result=java.util.Objects.requireNonNull(solve.get());
            synchronized (this) {
                if (observed!=epoch) continue;
                V existing=values.get(key);
                if(existing!=null) return existing;
                values.put(key,result);
                while(values.size()>limit) values.remove(values.keySet().iterator().next());
                return result;
            }
        }
    }
    public synchronized void invalidateBox(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        epoch++;
        int x0=Math.floorDiv(minX,16),y0=Math.floorDiv(minY,16),z0=Math.floorDiv(minZ,16);
        int x1=Math.floorDiv(maxX,16),y1=Math.floorDiv(maxY,16),z1=Math.floorDiv(maxZ,16);
        values.keySet().removeIf(k->k.x>=x0&&k.x<=x1&&k.y>=y0&&k.y<=y1&&k.z>=z0&&k.z<=z1);
    }
    public synchronized void clear() { epoch++; values.clear(); }
    public synchronized int size() { return values.size(); }
}
