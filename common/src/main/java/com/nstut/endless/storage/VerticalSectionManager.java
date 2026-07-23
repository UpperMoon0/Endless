package com.nstut.endless.storage;

import com.nstut.endless.config.EndlessConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashSet;
import java.util.Set;

public final class VerticalSectionManager {

    private static final int VERTICAL_LOAD_RADIUS = 128;
    private static final int PRESERVE_SECTIONS_ABOVE_GENERATION = 3;

    public static void purgeDistantSections(LevelChunk chunk, Iterable<ServerPlayer> players) {
        int minY = EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
        int maxY = EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight();
        int minLoadedSectionY = Integer.MAX_VALUE;
        int maxLoadedSectionY = Integer.MIN_VALUE;

        for (ServerPlayer player : players) {
            int py = player.blockPosition().getY();
            int minSy = Math.max(py - VERTICAL_LOAD_RADIUS, minY) >> 4;
            int maxSy = Math.min(py + VERTICAL_LOAD_RADIUS, maxY) >> 4;
            if (minSy < minLoadedSectionY) minLoadedSectionY = minSy;
            if (maxSy > maxLoadedSectionY) maxLoadedSectionY = maxSy;
        }

        if (minLoadedSectionY == Integer.MAX_VALUE) {
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        int maxSection = chunk.getMaxSection();

        int worldGenMaxSection = maxLoadedSectionY + PRESERVE_SECTIONS_ABOVE_GENERATION;

        for (int sy = minSection; sy < maxSection; sy++) {
            int idx = sy - minSection;
            if (idx < 0 || idx >= sections.length) continue;

            if (sy >= minLoadedSectionY && sy <= worldGenMaxSection) {
                continue;
            }

            LevelChunkSection sec = sections[idx];
            if (sec != null && sec.hasOnlyAir()) {
                sections[idx] = null;
                SectionArrayPool.reclaimSection(sec);
            }
        }
    }

    public static void tickPlayerChunks(LevelChunk chunk, Set<BlockPos> playerPositions) {
        if (playerPositions == null || playerPositions.isEmpty()) {
            return;
        }

        int minSectionY = Integer.MAX_VALUE;
        int maxSectionY = Integer.MIN_VALUE;

        for (BlockPos pos : playerPositions) {
            int sy = pos.getY() >> 4;
            if (sy < minSectionY) minSectionY = sy;
            if (sy > maxSectionY) maxSectionY = sy;
        }

        if (minSectionY == Integer.MAX_VALUE) {
            return;
        }

        minSectionY -= VERTICAL_LOAD_RADIUS >> 4;
        maxSectionY += VERTICAL_LOAD_RADIUS >> 4;

        int absMinY = EndlessConfig.getInstance().getBuildHeight().getMinBuildHeight();
        int absMaxY = EndlessConfig.getInstance().getBuildHeight().getMaxBuildHeight();

        minSectionY = Math.max(minSectionY, absMinY >> 4);
        maxSectionY = Math.min(maxSectionY, absMaxY >> 4);

        LevelChunkSection[] sections = chunk.getSections();
        int chunkMinSection = chunk.getMinSection();

        for (int sy = chunk.getMinSection(); sy < chunk.getMaxSection(); sy++) {
            int idx = sy - chunkMinSection;
            if (idx < 0 || idx >= sections.length) continue;

            if (sy >= minSectionY && sy <= maxSectionY) {
                continue;
            }

            LevelChunkSection sec = sections[idx];
            if (sec != null && sec.hasOnlyAir()) {
                sections[idx] = null;
                SectionArrayPool.reclaimSection(sec);
            }
        }
    }
}
