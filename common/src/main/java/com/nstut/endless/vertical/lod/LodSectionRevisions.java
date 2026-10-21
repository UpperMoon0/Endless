package com.nstut.endless.vertical.lod;

import java.util.HashMap;
import java.util.Map;

/** Source-owned revisions, independent of renderer hooks that another mod may cancel. */
public final class LodSectionRevisions {
    private final Map<Long,Map<Integer,Long>> columns = new HashMap<>();
    private long generation;
    private static long column(int x,int z) { return ((long)x<<32) | (z&0xffffffffL); }
    public long generation() { return generation; }
    public long revision(int x,int y,int z) {
        var sections=columns.get(column(x,z));
        return sections==null?0:sections.getOrDefault(y,0L);
    }
    public void touchSection(int x,int y,int z) {
        columns.computeIfAbsent(column(x,z),ignored->new HashMap<>()).put(y,++generation);
    }
    public void touchPage(int x,int page,int z) {
        var sections=columns.computeIfAbsent(column(x,z),ignored->new HashMap<>());
        long next=++generation;
        for(int y=page*32;y<page*32+32;y++) sections.put(y,next);
    }
    public void unloadColumn(int x,int z) { columns.remove(column(x,z)); generation++; }
    public void clear() { columns.clear(); generation++; }
}
