package com.nstut.endless.vertical;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runtime sparse vertical storage attached to one Level instance. */
public final class MinecraftVerticalWorld {
    private final Level level;
    private final VerticalPageDiskStorage disk;
    private final Map<Long, SparseVerticalColumn<LevelChunkSection>> columns = new HashMap<>();
    private final Set<VerticalPagePos> attemptedLoads = new HashSet<>();
    private final Set<VerticalPagePos> dirtyPages = new HashSet<>();
    private final Map<VerticalPagePos, Long> revisions = new HashMap<>();
    private long nextRevision = 1L;

    MinecraftVerticalWorld(Level level) {
        this.level = level;
        this.disk = level instanceof ServerLevel serverLevel
            ? new VerticalPageDiskStorage(serverLevel)
            : null;
    }

    public Level level() {
        return level;
    }

    public synchronized BlockState getBlockState(BlockPos pos) {
        LevelChunkSection section = getSection(pos.getX() >> 4, pos.getZ() >> 4,
            VerticalPageLayout.sectionYForBlockY(pos.getY()), false);
        if (section == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    public synchronized FluidState getFluidState(BlockPos pos) {
        LevelChunkSection section = getSection(pos.getX() >> 4, pos.getZ() >> 4,
            VerticalPageLayout.sectionYForBlockY(pos.getY()), false);
        if (section == null) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return section.getFluidState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    public synchronized BlockState setBlockState(BlockPos pos, BlockState state) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int sectionY = VerticalPageLayout.sectionYForBlockY(pos.getY());
        LevelChunkSection section = getSection(chunkX, chunkZ, sectionY, !state.isAir());
        if (section == null) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState old = section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        VerticalPagePos pagePos = VerticalPagePos.fromChunkAndSection(chunkX, sectionY, chunkZ);

        if (section.hasOnlyAir()) {
            long columnKey = ChunkPos.asLong(chunkX, chunkZ);
            SparseVerticalColumn<LevelChunkSection> column = columns.get(columnKey);
            if (column != null) {
                column.removeSection(sectionY);
                if (column.isEmpty()) {
                    columns.remove(columnKey);
                }
            }
        }

        markDirty(pagePos);
        return old;
    }

    public synchronized VerticalPageSnapshot snapshot(VerticalPagePos pos, boolean loadFromDisk) {
        VerticalPage<LevelChunkSection> page = getPage(pos, false, loadFromDisk);
        if (page == null || page.isEmpty()) {
            return null;
        }
        return VerticalPageSnapshot.fromPage(pos, revisions.getOrDefault(pos, 0L), page);
    }

    public synchronized void applySnapshot(VerticalPageSnapshot snapshot) {
        VerticalPagePos pos = snapshot.pos();
        long currentRevision = revisions.getOrDefault(pos, Long.MIN_VALUE);
        if (snapshot.revision() < currentRevision) {
            return;
        }

        VerticalPage<LevelChunkSection> decoded = snapshot.decode(level);
        long key = ChunkPos.asLong(pos.chunkX(), pos.chunkZ());
        SparseVerticalColumn<LevelChunkSection> targetColumn =
            columns.computeIfAbsent(key, ignored -> new SparseVerticalColumn<>());
        targetColumn.removePage(pos.pageY());
        decoded.forEachOccupiedSection((sectionY, section) -> targetColumn.putSection(sectionY, section));
        if (targetColumn.isEmpty()) {
            columns.remove(key);
        }
        attemptedLoads.add(pos);
        revisions.put(pos, snapshot.revision());
    }

    public synchronized List<Integer> loadedPageYs(int chunkX, int chunkZ) {
        SparseVerticalColumn<LevelChunkSection> column = columns.get(ChunkPos.asLong(chunkX, chunkZ));
        return column == null ? List.of() : column.pageYs();
    }

    public synchronized boolean pageExists(VerticalPagePos pos) {
        SparseVerticalColumn<LevelChunkSection> column = columns.get(ChunkPos.asLong(pos.chunkX(), pos.chunkZ()));
        if (column != null && column.getPage(pos.pageY()) != null) {
            return true;
        }
        return disk != null && disk.exists(pos);
    }

    public synchronized void flushDirty() {
        if (disk == null || dirtyPages.isEmpty()) {
            return;
        }

        List<VerticalPagePos> pending = new ArrayList<>(dirtyPages);
        for (VerticalPagePos pos : pending) {
            try {
                VerticalPage<LevelChunkSection> page = getPage(pos, false, false);
                if (page == null || page.isEmpty()) {
                    disk.delete(pos);
                } else {
                    disk.save(pos, page);
                }
                dirtyPages.remove(pos);
            } catch (IOException e) {
                System.err.println("Endless: failed to persist vertical page " + pos + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public synchronized void close() {
        flushDirty();
        columns.clear();
        attemptedLoads.clear();
        dirtyPages.clear();
        revisions.clear();
    }

    private LevelChunkSection getSection(int chunkX, int chunkZ, int sectionY, boolean create) {
        VerticalPagePos pagePos = VerticalPagePos.fromChunkAndSection(chunkX, sectionY, chunkZ);
        VerticalPage<LevelChunkSection> page = getPage(pagePos, create, true);
        if (page == null) {
            return null;
        }
        LevelChunkSection section = page.getSection(sectionY);
        if (section == null && create) {
            section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
            page.putSection(sectionY, section);
        }
        return section;
    }

    private VerticalPage<LevelChunkSection> getPage(VerticalPagePos pos, boolean create, boolean loadFromDisk) {
        long key = ChunkPos.asLong(pos.chunkX(), pos.chunkZ());
        SparseVerticalColumn<LevelChunkSection> existingColumn = columns.get(key);
        VerticalPage<LevelChunkSection> existingPage =
            existingColumn == null ? null : existingColumn.getPage(pos.pageY());
        if (existingPage != null) {
            return existingPage;
        }

        if (loadFromDisk && disk != null && attemptedLoads.add(pos)) {
            try {
                Optional<VerticalPage<LevelChunkSection>> loaded = disk.load(pos);
                if (loaded.isPresent()) {
                    SparseVerticalColumn<LevelChunkSection> targetColumn =
                        columns.computeIfAbsent(key, ignored -> new SparseVerticalColumn<>());
                    VerticalPage<LevelChunkSection> loadedPage = loaded.get();
                    loadedPage.forEachOccupiedSection(
                        (sectionY, section) -> targetColumn.putSection(sectionY, section));
                    return targetColumn.getPage(pos.pageY());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load Endless vertical page " + pos, e);
            }
        }

        if (!create) {
            return null;
        }
        SparseVerticalColumn<LevelChunkSection> targetColumn =
            columns.computeIfAbsent(key, ignored -> new SparseVerticalColumn<>());
        attemptedLoads.add(pos);
        return targetColumn.getOrCreatePage(pos.pageY());
    }

    private void markDirty(VerticalPagePos pos) {
        dirtyPages.add(pos);
        revisions.put(pos, nextRevision++);
    }
}
