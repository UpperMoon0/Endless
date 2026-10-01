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
