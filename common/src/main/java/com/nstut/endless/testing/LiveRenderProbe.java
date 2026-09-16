package com.nstut.endless.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Live-E2E instrumentation proving the real sparse render paths were exercised. */
public final class LiveRenderProbe {
    private static final Set<Integer> VIEW_AREA_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RENDER_CHUNK_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RENDER_GRAPH_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> VIEW_AREA_EXACT_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> RENDER_GRAPH_EXACT_SECTIONS = ConcurrentHashMap.newKeySet();

    private LiveRenderProbe() {}

    private static boolean armed() {
        return LiveJoinTest.isArmed() || LiveSameJvmRejoinTest.isArmed();
    }

    public static void recordViewArea(BlockPos pos) {
        if (!armed()) return;
        VIEW_AREA_SECTIONS.add(SectionPos.blockToSectionCoord(pos.getY()));
        VIEW_AREA_EXACT_SECTIONS.add(sectionKey(pos));
    }

    public static void recordRenderChunk(BlockPos pos) {
        if (armed()) RENDER_CHUNK_SECTIONS.add(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static boolean sawViewArea(BlockPos pos) {
        return VIEW_AREA_SECTIONS.contains(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static boolean sawViewAreaExact(BlockPos pos) {
        return VIEW_AREA_EXACT_SECTIONS.contains(sectionKey(pos));
    }

    public static void recordRenderGraph(BlockPos pos) {
        if (!armed()) return;
        RENDER_GRAPH_SECTIONS.add(SectionPos.blockToSectionCoord(pos.getY()));
        RENDER_GRAPH_EXACT_SECTIONS.add(sectionKey(pos));
    }

    public static boolean sawRenderChunk(BlockPos pos) {
        return RENDER_CHUNK_SECTIONS.contains(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static boolean sawRenderGraph(BlockPos pos) {
        return RENDER_GRAPH_SECTIONS.contains(SectionPos.blockToSectionCoord(pos.getY()));
    }

    public static boolean sawRenderGraphExact(BlockPos pos) {
        return RENDER_GRAPH_EXACT_SECTIONS.contains(sectionKey(pos));
    }

    private static long sectionKey(BlockPos pos) {
        return SectionPos.asLong(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ()));
    }
}
