package com.nstut.endless.compat.create;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageLayout;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/** Discover only allocated sparse pages, rather than scanning the entire logical height. */
public final class CreateLogicalGeometry {
    private CreateLogicalGeometry() {}
    /** Skip empty sparse gaps while preserving randomTeleport's first solid floor. */
    public static int teleportFloor(Level level, double targetX, double targetY, double targetZ) {
        BlockPos target = BlockPos.containing(targetX, targetY, targetZ);
        int ceiling = target.getY() - 1;
        var pages = new java.util.TreeSet<Integer>(java.util.Comparator.reverseOrder());
        pages.addAll(EndlessVerticalEngine.world(level).knownPageYs(target.getX() >> 4, target.getZ() >> 4));
        // Dense terrain is allocated independently of sparse pages.
        for (int page = VerticalPageLayout.pageYForBlockY(level.getMinBuildHeight());
             page <= VerticalPageLayout.pageYForBlockY(level.getMaxBuildHeight() - 1); page++) pages.add(page);
        for (int page : pages) {
            int low = Math.max(EndlessHeights.getMinBuildHeight(), VerticalPageLayout.pageMinBlockY(page));
            int high = Math.min(ceiling, Math.min(EndlessHeights.getMaxBuildHeight() - 1, VerticalPageLayout.pageMaxBlockY(page)));
            for (int y = high; y >= low; y--)
                if (level.getBlockState(new BlockPos(target.getX(), y, target.getZ())).blocksMotion()) return y;
        }
        // Native loop sees no floor and refuses; do not scan millions of empty cells.
        return Integer.MIN_VALUE;
    }
    /** Preserve unloaded-neighbour refusal without vanilla's dense-Y early rejection. */
    public static boolean isAreaLoaded(Level level, BlockPos center, int range) {
        if (!EndlessLogicalHeights.isActive()) return level.hasChunksAt(center.offset(-range, -range, -range), center.offset(range, range, range));
        if ((long) center.getY() + range < EndlessHeights.getMinBuildHeight()
            || (long) center.getY() - range >= EndlessHeights.getMaxBuildHeight()) return false;
        for (int x = (center.getX() - range) >> 4; x <= (center.getX() + range) >> 4; x++)
            for (int z = (center.getZ() - range) >> 4; z <= (center.getZ() + range) >> 4; z++)
                if (!level.getChunkSource().hasChunk(x, z)) return false;
        return true;
    }
    public static Stream<BlockPos> columnPositions(LevelAccessor accessor, BlockPos first, BlockPos last) {
        Stream<BlockPos> dense = BlockPos.betweenClosedStream(first, last).map(BlockPos::immutable);
        if (!EndlessLogicalHeights.isActive() || !(accessor instanceof Level level)) return dense;
        int x = first.getX(), z = first.getZ();
        Stream<BlockPos> sparse = EndlessVerticalEngine.world(level).knownPageYs(x >> 4, z >> 4).stream()
            .flatMap(page -> IntStream.range(VerticalPageLayout.pageMinBlockY(page), VerticalPageLayout.pageMaxBlockY(page) + 1)
                .filter(y -> !EndlessHeights.isOutsideBuildHeight(y) && EndlessHeights.isOutsideDenseBuildHeight(y))
                .mapToObj(y -> new BlockPos(x, y, z)));
        return Stream.concat(dense.filter(pos -> !EndlessHeights.isOutsideBuildHeight(pos.getY())), sparse);
    }
}
