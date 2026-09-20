package com.nstut.endless.vertical;

import com.nstut.endless.mixin.accessor.MinecraftVerticalWorldAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.List;
import java.util.Map;

/** Runs vanilla-style random block/fluid ticks for currently loaded sparse sections. */
public final class SparseRandomTicker {
    private SparseRandomTicker() {}

    public static void tickLoadedColumn(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        if (randomTickSpeed <= 0) {
            return;
        }

        MinecraftVerticalWorld world = EndlessVerticalEngine.world(level);
        synchronized (world) {
            Map<Long, SparseVerticalColumn<LevelChunkSection>> columns =
                ((MinecraftVerticalWorldAccessor) (Object) world).endless$getColumns();
            SparseVerticalColumn<LevelChunkSection> column =
                columns.get(ChunkPos.asLong(chunk.getPos().x, chunk.getPos().z));
            if (column == null || column.isEmpty()) {
                return;
            }

            int baseX = chunk.getPos().getMinBlockX();
            int baseZ = chunk.getPos().getMinBlockZ();
            List<Integer> pageYs = column.pageYs();
            for (int pageY : pageYs) {
                VerticalPage<LevelChunkSection> page = column.getPage(pageY);
                if (page == null) {
                    continue;
                }
                page.forEachOccupiedSection((sectionY, section) ->
                    tickSection(level, section, sectionY, baseX, baseZ, randomTickSpeed));
            }
        }
    }

    private static void tickSection(
        ServerLevel level,
        LevelChunkSection section,
        int sectionY,
        int baseX,
        int baseZ,
        int randomTickSpeed
    ) {
        if (!section.isRandomlyTicking()) {
            return;
        }
        int baseY = SectionPos.sectionToBlockCoord(sectionY);
        for (int i = 0; i < randomTickSpeed; i++) {
            BlockPos pos = level.getBlockRandomPos(baseX, baseY, baseZ, 15);
            BlockState state = section.getBlockState(
                pos.getX() - baseX,
                pos.getY() - baseY,
                pos.getZ() - baseZ);
            if (state.isRandomlyTicking()) {
                state.randomTick(level, pos, level.random);
            }
            FluidState fluid = state.getFluidState();
            if (fluid.isRandomlyTicking()) {
                fluid.randomTick(level, pos, level.random);
            }
        }
    }
}
