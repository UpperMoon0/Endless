package com.nstut.endless.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Live-E2E instrumentation proving the real sparse render paths were exercised. */
public final class LiveRenderProbe {
    private static final Set<Integer> VIEW_AREA_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RENDER_CHUNK_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> RENDER_GRAPH_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> VIEW_AREA_EXACT_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> RENDER_GRAPH_EXACT_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<ExactBlockPos> COMPILED_BLOCK_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<Long> COMPILED_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> BLOCK_ENTITY_REFRESH_QUEUED = ConcurrentHashMap.newKeySet();
    private static final Set<Long> BLOCK_ENTITY_REFRESH_DIRTIED = ConcurrentHashMap.newKeySet();
    private static final Set<ExactBlockPos> RENDER_CHUNK_BLOCK_ENTITIES = ConcurrentHashMap.newKeySet();

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

    /** Records a completed render section and any block entities captured into it. */
    public static void recordCompiledSection(BlockPos origin, Collection<BlockEntity> blockEntities) {
        if (!armed()) return;
        COMPILED_SECTIONS.add(sectionKey(origin));
        for (BlockEntity blockEntity : blockEntities) {
            COMPILED_BLOCK_ENTITIES.add(exactBlockKey(blockEntity.getBlockPos()));
        }
    }

    public static void recordBlockEntityRefreshQueued(BlockPos pos) {
        if (armed()) BLOCK_ENTITY_REFRESH_QUEUED.add(sectionKey(pos));
    }

    public static void recordBlockEntityRefreshDirtied(BlockPos pos) {
        if (armed()) BLOCK_ENTITY_REFRESH_DIRTIED.add(sectionKey(pos));
    }

    public static void recordRenderChunkBlockEntity(BlockPos pos, BlockEntity blockEntity) {
        if (armed() && blockEntity != null) RENDER_CHUNK_BLOCK_ENTITIES.add(exactBlockKey(pos));
    }

    public static boolean sawBlockEntityCompiled(BlockPos pos) {
        return COMPILED_BLOCK_ENTITIES.contains(exactBlockKey(pos));
    }

    public static boolean sawSectionCompiled(BlockPos pos) {
        return COMPILED_SECTIONS.contains(sectionKey(pos));
    }

    public static boolean sawBlockEntityRefreshQueued(BlockPos pos) {
        return BLOCK_ENTITY_REFRESH_QUEUED.contains(sectionKey(pos));
    }

    public static boolean sawBlockEntityRefreshDirtied(BlockPos pos) {
        return BLOCK_ENTITY_REFRESH_DIRTIED.contains(sectionKey(pos));
    }

    public static boolean sawRenderChunkBlockEntity(BlockPos pos) {
        return RENDER_CHUNK_BLOCK_ENTITIES.contains(exactBlockKey(pos));
    }

    /** Clear evidence at a world transition so a prior session cannot satisfy a rejoin assertion. */
    public static void resetWorldEvidence() {
        VIEW_AREA_SECTIONS.clear();
        RENDER_CHUNK_SECTIONS.clear();
        RENDER_GRAPH_SECTIONS.clear();
        VIEW_AREA_EXACT_SECTIONS.clear();
        RENDER_GRAPH_EXACT_SECTIONS.clear();
        COMPILED_BLOCK_ENTITIES.clear();
        COMPILED_SECTIONS.clear();
        BLOCK_ENTITY_REFRESH_QUEUED.clear();
        BLOCK_ENTITY_REFRESH_DIRTIED.clear();
        RENDER_CHUNK_BLOCK_ENTITIES.clear();
    }

    private static long sectionKey(BlockPos pos) {
        return SectionPos.asLong(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getY()),
            SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private static ExactBlockPos exactBlockKey(BlockPos pos) {
        return new ExactBlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private record ExactBlockPos(int x, int y, int z) {}
}
