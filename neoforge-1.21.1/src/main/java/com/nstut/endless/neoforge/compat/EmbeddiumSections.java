package com.nstut.endless.neoforge.compat;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;

public final class EmbeddiumSections {
    private EmbeddiumSections() {}

    public static LevelChunkSection get(Level level, ChunkAccess chunk, int sectionY) {
        int blockY = sectionY << 4;
        if (EndlessLogicalHeights.isActive() && !EndlessLogicalHeights.contains(blockY)) return null;
        int index = level.getSectionIndexFromSectionY(sectionY);
        LevelChunkSection[] dense = chunk.getSections();
        if (index >= 0 && index < dense.length) return dense[index];
        if (!EndlessVerticalEngine.isExtendedY(level, blockY)) return null;
        return EndlessVerticalEngine.world(level).getSectionForRendering(chunk.getPos().x, sectionY, chunk.getPos().z);
    }

    public static void prepareBlockEntities(LevelChunk chunk, LevelChunkSection section, SectionPos pos) {
        if (!EndlessVerticalEngine.isExtendedY(chunk.getLevel(), pos.minBlockY())) return;
        // Page snapshots replace palettes directly. Vanilla's render facade
        // lazily asks the chunk for each BE; Embeddium instead copies its map.
        // Reconcile this section on the render thread before that map is cloned.
        for (BlockPos existing : new ArrayList<>(chunk.getBlockEntities().keySet())) {
            if ((existing.getY() >> 4) != pos.getY()) continue;
            BlockState state = section == null ? Blocks.AIR.defaultBlockState()
                : section.getBlockState(existing.getX() & 15, existing.getY() & 15, existing.getZ() & 15);
            var entity = chunk.getBlockEntities().get(existing);
            if (!state.hasBlockEntity() || entity.getBlockState().getBlock() != state.getBlock()) chunk.removeBlockEntity(existing);
        }
        if (section == null || !section.maybeHas(BlockState::hasBlockEntity)) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState state = section.getBlockState(x, y, z);
            if (!state.hasBlockEntity()) continue;
            cursor.set(pos.minBlockX() + x, pos.minBlockY() + y, pos.minBlockZ() + z);
            var entity = chunk.getBlockEntity(cursor.immutable(), LevelChunk.EntityCreationType.IMMEDIATE);
            if (entity != null) entity.setBlockState(state);
        }
    }
}
