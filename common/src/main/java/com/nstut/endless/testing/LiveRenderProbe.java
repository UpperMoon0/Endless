package com.nstut.endless.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Live-E2E instrumentation proving the real sparse render paths were exercised. */
public final class LiveRenderProbe {
    private static final Set<Integer> VIEW_AREA_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RENDER_CHUNK_SECTIONS = ConcurrentHashMap.newKeySet();

    private LiveRenderProbe() {}

    public static void recordViewArea(BlockPos pos) {
        if (LiveJoinTest.isArmed()) VIEW_AREA_SECTIONS.add(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static void recordRenderChunk(BlockPos pos) {
        if (LiveJoinTest.isArmed()) RENDER_CHUNK_SECTIONS.add(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static boolean sawViewArea(BlockPos pos) {
        return VIEW_AREA_SECTIONS.contains(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static boolean sawRenderChunk(BlockPos pos) {
        return RENDER_CHUNK_SECTIONS.contains(SectionPos.blockToSectionCoord(pos.getY()));
    }
}
