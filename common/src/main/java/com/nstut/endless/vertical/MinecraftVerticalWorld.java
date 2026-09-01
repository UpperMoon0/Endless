package com.nstut.endless.vertical;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runtime sparse vertical storage attached to one Level instance. */
public final class MinecraftVerticalWorld {
    private static final int SKY_CACHE_LIMIT = 65_536;

    private final Level level;
    private final VerticalPageDiskStorage disk;
    private final Map<Long, SparseVerticalColumn<LevelChunkSection>> columns = new HashMap<>();
    private final Set<VerticalPagePos> attemptedLoads = new HashSet<>();
    private final Set<VerticalPagePos> dirtyPages = new HashSet<>();
    private final Map<VerticalPagePos, Long> revisions = new HashMap<>();
    private final Map<HeightKey, Integer> heightCache = new HashMap<>();
    private final Map<BlockKey, Byte> blockLight = new HashMap<>();
    private final Map<BlockKey, Integer> skyLight = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, Integer> eldest) {
            return size() > SKY_CACHE_LIMIT;
        }
    };
    private boolean blockLightDirty = true;
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
        if (old == state) {
            return old;
        }
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
        invalidateForBlockChange(pos);
        return old;
    }

    /** First available Y (highest matching block + 1), or MIN_VALUE if no sparse block matches. */
    public synchronized int getExtendedHeight(Heightmap.Types type, int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        HeightKey key = new HeightKey(
            ChunkPos.asLong(chunkX, chunkZ),
            (blockX & 15) | ((blockZ & 15) << 4),
            type);
        Integer cached = heightCache.get(key);
        if (cached != null) {
            return cached;
        }
        int top = findExtendedTop(type, chunkX, chunkZ, blockX & 15, blockZ & 15);
        int result = top == Integer.MIN_VALUE ? Integer.MIN_VALUE : top + 1;
        heightCache.put(key, result);
        return result;
    }

    public synchronized int getBrightness(LightLayer layer, BlockPos pos) {
        if (layer == LightLayer.BLOCK) {
            ensureBlockLight();
            return Byte.toUnsignedInt(blockLight.getOrDefault(BlockKey.of(pos), (byte) 0));
        }
        BlockKey key = BlockKey.of(pos);
        Integer cached = skyLight.get(key);
        if (cached != null) {
            return cached;
        }
        int value = computeSkyLight(pos);
        skyLight.put(key, value);
        return value;
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
        invalidateColumn(key);
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
        for (VerticalPagePos pos : new ArrayList<>(dirtyPages)) {
            persist(pos);
        }
    }

    public synchronized void unloadColumn(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (disk != null) {
            for (VerticalPagePos pos : new ArrayList<>(dirtyPages)) {
                if (pos.chunkX() == chunkX && pos.chunkZ() == chunkZ) {
                    persist(pos);
                }
            }
        }
        columns.remove(key);
        attemptedLoads.removeIf(pos -> pos.chunkX() == chunkX && pos.chunkZ() == chunkZ);
        dirtyPages.removeIf(pos -> pos.chunkX() == chunkX && pos.chunkZ() == chunkZ);
        revisions.keySet().removeIf(pos -> pos.chunkX() == chunkX && pos.chunkZ() == chunkZ);
        invalidateColumn(key);
    }

    public synchronized void close() {
        flushDirty();
        columns.clear();
        attemptedLoads.clear();
        dirtyPages.clear();
        revisions.clear();
        heightCache.clear();
        blockLight.clear();
        skyLight.clear();
    }

    private void persist(VerticalPagePos pos) {
        try {
            VerticalPage<LevelChunkSection> page = getPage(pos, false, false);
            if (page == null || page.isEmpty()) {
                disk.delete(pos);
            } else {
                disk.save(pos, page);
            }
            dirtyPages.remove(pos);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist Endless vertical page " + pos, e);
        }
    }

    private int findExtendedTop(Heightmap.Types type, int chunkX, int chunkZ, int localX, int localZ) {
        List<Integer> pageYs = allPageYs(chunkX, chunkZ);
        for (int pageIndex = pageYs.size() - 1; pageIndex >= 0; pageIndex--) {
            int pageY = pageYs.get(pageIndex);
            VerticalPage<LevelChunkSection> page = getPage(
                new VerticalPagePos(chunkX, pageY, chunkZ), false, true);
            if (page == null) {
                continue;
            }
            for (int localSection = VerticalPageLayout.SECTIONS_PER_PAGE - 1; localSection >= 0; localSection--) {
                LevelChunkSection section = page.getLocalSection(localSection);
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                int sectionY = VerticalPageLayout.sectionY(pageY, localSection);
                for (int localY = 15; localY >= 0; localY--) {
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    if (heightMatches(type, state)) {
                        return sectionY * 16 + localY;
                    }
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private List<Integer> allPageYs(int chunkX, int chunkZ) {
        HashSet<Integer> result = new HashSet<>();
        SparseVerticalColumn<LevelChunkSection> column = columns.get(ChunkPos.asLong(chunkX, chunkZ));
        if (column != null) {
            result.addAll(column.pageYs());
        }
        if (disk != null) {
            result.addAll(disk.pageYs(chunkX, chunkZ));
        }
        ArrayList<Integer> sorted = new ArrayList<>(result);
        Collections.sort(sorted);
        return sorted;
    }

    private static boolean heightMatches(Heightmap.Types type, BlockState state) {
        return switch (type) {
            case WORLD_SURFACE_WG, WORLD_SURFACE -> !state.isAir();
            case OCEAN_FLOOR_WG, OCEAN_FLOOR -> state.blocksMotion();
            case MOTION_BLOCKING -> state.blocksMotion() || !state.getFluidState().isEmpty();
            case MOTION_BLOCKING_NO_LEAVES ->
                (state.blocksMotion() || !state.getFluidState().isEmpty())
                    && !(state.getBlock() instanceof LeavesBlock);
        };
    }

    private void ensureBlockLight() {
        if (!blockLightDirty) {
            return;
        }
        blockLightDirty = false;
        blockLight.clear();
        ArrayDeque<LightNode> queue = new ArrayDeque<>();

        for (Map.Entry<Long, SparseVerticalColumn<LevelChunkSection>> columnEntry : columns.entrySet()) {
            int chunkX = ChunkPos.getX(columnEntry.getKey());
            int chunkZ = ChunkPos.getZ(columnEntry.getKey());
            SparseVerticalColumn<LevelChunkSection> column = columnEntry.getValue();
            for (int pageY : column.pageYs()) {
                VerticalPage<LevelChunkSection> page = column.getPage(pageY);
                if (page == null) {
                    continue;
                }
                page.forEachOccupiedSection((sectionY, section) -> {
                    if (section.hasOnlyAir()) {
                        return;
                    }
                    int baseX = chunkX << 4;
                    int baseY = sectionY << 4;
                    int baseZ = chunkZ << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                int emission = state.getLightEmission();
                                if (emission > 0) {
                                    BlockKey key = new BlockKey(baseX + x, baseY + y, baseZ + z);
                                    int old = Byte.toUnsignedInt(blockLight.getOrDefault(key, (byte) 0));
                                    if (emission > old) {
                                        blockLight.put(key, (byte) emission);
                                        queue.addLast(new LightNode(key, emission));
                                    }
                                }
                            }
                        }
                    }
                });
            }
        }

        while (!queue.isEmpty()) {
            LightNode node = queue.removeFirst();
            int currentStored = Byte.toUnsignedInt(blockLight.getOrDefault(node.pos, (byte) 0));
            if (node.light < currentStored || node.light <= 1) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                BlockKey next = node.pos.relative(direction);
                if (!EndlessLogicalHeights.contains(next.y)) {
                    continue;
                }
                BlockPos nextPos = next.toBlockPos();
                BlockState nextState = level.getBlockState(nextPos);
                int attenuation = Math.max(1, nextState.getLightBlock(level, nextPos));
                int propagated = node.light - attenuation;
                if (propagated <= 0) {
                    continue;
                }
                int old = Byte.toUnsignedInt(blockLight.getOrDefault(next, (byte) 0));
                if (propagated > old) {
                    blockLight.put(next, (byte) propagated);
                    queue.addLast(new LightNode(next, propagated));
                }
            }
        }
    }

    private int computeSkyLight(BlockPos pos) {
        if (isSkyExposed(pos.getX(), pos.getY(), pos.getZ())) {
            return 15;
        }
        int best = 0;
        Direction[] paths = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (Direction direction : paths) {
            BlockPos.MutableBlockPos cursor = pos.mutable();
            int cost = 0;
            for (int distance = 1; distance <= 15 && cost < 15; distance++) {
                cursor.move(direction);
                if (!EndlessLogicalHeights.contains(cursor.getY())) {
                    break;
                }
                BlockState state = level.getBlockState(cursor);
                cost += Math.max(1, state.getLightBlock(level, cursor));
                if (cost >= 15) {
                    break;
                }
                if (isSkyExposed(cursor.getX(), cursor.getY(), cursor.getZ())) {
                    best = Math.max(best, 15 - cost);
                    break;
                }
            }
        }
        return best;
    }

    private boolean isSkyExposed(int x, int y, int z) {
        int extended = getExtendedHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int extendedTop = extended == Integer.MIN_VALUE ? Integer.MIN_VALUE : extended - 1;
        LevelChunk core = level.getChunk(x >> 4, z >> 4);
        int coreTop = core.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        return y > Math.max(coreTop, extendedTop);
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
                    invalidateColumn(key);
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

    private void invalidateForBlockChange(BlockPos pos) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        int local = (pos.getX() & 15) | ((pos.getZ() & 15) << 4);
        heightCache.keySet().removeIf(heightKey -> heightKey.chunkKey == key && heightKey.localColumn == local);
        blockLightDirty = true;
        skyLight.clear();
    }

    private void invalidateColumn(long key) {
        heightCache.keySet().removeIf(heightKey -> heightKey.chunkKey == key);
        blockLightDirty = true;
        blockLight.clear();
        skyLight.clear();
    }

    private record HeightKey(long chunkKey, int localColumn, Heightmap.Types type) {}

    private record BlockKey(int x, int y, int z) {
        static BlockKey of(BlockPos pos) {
            return new BlockKey(pos.getX(), pos.getY(), pos.getZ());
        }

        BlockKey relative(Direction direction) {
            return new BlockKey(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private record LightNode(BlockKey pos, int light) {}
}
