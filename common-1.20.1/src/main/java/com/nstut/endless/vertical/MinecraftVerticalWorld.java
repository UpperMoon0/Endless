package com.nstut.endless.vertical;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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
    private static final int BLOCK_LIGHT_CACHE_LIMIT = 65_536;
    private static final int SKY_CACHE_LIMIT = 65_536;
    /** Emission 15 loses at least one level per block, so only 14 steps can remain lit. */
    private static final int BLOCK_LIGHT_SOURCE_RADIUS = 14;
    private static final int BLOCK_LIGHT_INVALIDATION_RADIUS = 15;

    private final Level level;
    private final VerticalPageDiskStorage disk;
    private final Map<Long, SparseVerticalColumn<LevelChunkSection>> columns = new HashMap<>();
    private final Set<VerticalPagePos> attemptedLoads = new HashSet<>();
    private final Set<VerticalPagePos> dirtyPages = new HashSet<>();
    private final Map<VerticalPagePos, Long> revisions = new HashMap<>();
    private final Map<HeightKey, Integer> heightCache = new HashMap<>();
    private final Map<BlockKey, Byte> blockLight = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, Byte> eldest) {
            return size() > BLOCK_LIGHT_CACHE_LIMIT;
        }
    };
    private final Map<BlockKey, Integer> skyLight = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, Integer> eldest) {
            return size() > SKY_CACHE_LIMIT;
        }
    };
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

    /** Client render snapshots may read an existing page but must never allocate one. */
    public synchronized LevelChunkSection getSectionForRendering(int chunkX, int sectionY, int chunkZ) {
        return getSection(chunkX, chunkZ, sectionY, false);
    }

    /**
     * Solve all 4096 target cells together. Only emitters within 14 blocks of
     * the section can contribute. Palette checks skip empty/non-emissive
     * sections, avoiding 4096 separate neighborhood searches for each clone.
     * The returned layer is owned by the immutable render snapshot.
     */
    public synchronized DataLayer copyRenderBlockLight(SectionPos sectionPos) {
        int minX = sectionPos.minBlockX() - BLOCK_LIGHT_SOURCE_RADIUS;
        int maxX = sectionPos.maxBlockX() + BLOCK_LIGHT_SOURCE_RADIUS;
        int minY = sectionPos.minBlockY() - BLOCK_LIGHT_SOURCE_RADIUS;
        int maxY = sectionPos.maxBlockY() + BLOCK_LIGHT_SOURCE_RADIUS;
        int minZ = sectionPos.minBlockZ() - BLOCK_LIGHT_SOURCE_RADIUS;
        int maxZ = sectionPos.maxBlockZ() + BLOCK_LIGHT_SOURCE_RADIUS;
        Map<BlockKey, Byte> local = new HashMap<>();
        ArrayDeque<LightNode> queue = new ArrayDeque<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk dense = level.getChunk(cx, cz);
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    LevelChunkSection source = index >= 0 && index < dense.getSections().length
                        ? dense.getSections()[index] : getSection(cx, cz, sy, false);
                    if (source == null || !source.maybeHas(state -> state.getLightEmission() > 0)) continue;
                    for (int y = Math.max(minY, sy << 4); y <= Math.min(maxY, (sy << 4) + 15); y++) {
                        if (!EndlessLogicalHeights.contains(y)) continue;
                        for (int z = Math.max(minZ, cz << 4); z <= Math.min(maxZ, (cz << 4) + 15); z++) {
                            for (int x = Math.max(minX, cx << 4); x <= Math.min(maxX, (cx << 4) + 15); x++) {
                                int emission = source.getBlockState(x & 15, y & 15, z & 15).getLightEmission();
                                if (emission > 0) {
                                    BlockKey key = new BlockKey(x, y, z);
                                    local.put(key, (byte) emission);
                                    queue.addLast(new LightNode(key, emission));
                                }
                            }
                        }
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            LightNode node = queue.removeFirst();
            if (node.light <= 1 || node.light < Byte.toUnsignedInt(local.getOrDefault(node.pos, (byte) 0))) continue;
            BlockPos from = node.pos.toBlockPos();
            BlockState fromState = level.getBlockState(from);
            for (Direction direction : Direction.values()) {
                BlockKey next = node.pos.relative(direction);
                if (next.x < minX || next.x > maxX || next.y < minY || next.y > maxY
                    || next.z < minZ || next.z > maxZ || !EndlessLogicalHeights.contains(next.y)) continue;
                BlockPos to = next.toBlockPos();
                BlockState toState = level.getBlockState(to);
                int propagated = node.light - Math.max(1, toState.getLightBlock(level, to));
                if (propagated <= Byte.toUnsignedInt(local.getOrDefault(next, (byte) 0))
                    || lightFacesOcclude(fromState, from, toState, to, direction)) continue;
                local.put(next, (byte) propagated);
                queue.addLast(new LightNode(next, propagated));
            }
        }
        DataLayer result = new DataLayer();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockKey key = new BlockKey(sectionPos.minBlockX() + x, sectionPos.minBlockY() + y, sectionPos.minBlockZ() + z);
            byte value = local.getOrDefault(key, (byte) 0);
            result.set(x, y, z, Byte.toUnsignedInt(value));
            // Every target cell has a complete neighborhood in this solve.
            blockLight.put(key, value);
        }
        return result;
    }

    /** Snapshot skylight with one height query per halo column, not per ray step. */
    public synchronized DataLayer copyRenderSkyLight(SectionPos section) {
        final int radius = 15;
        final int width = 16 + radius * 2;
        int minX = section.minBlockX() - radius;
        int minZ = section.minBlockZ() - radius;
        int[][] tops = new int[width][width];
        for (int x = 0; x < width; x++) for (int z = 0; z < width; z++) {
            tops[x][z] = skyOcclusionTop(minX + x, minZ + z);
        }
        DataLayer result = new DataLayer();
        Direction[] paths = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int centerX = x + radius, centerZ = z + radius;
            int centerTop = tops[centerX][centerZ];
            int lowestRayTop = centerTop;
            for (int distance = 1; distance <= radius; distance++) {
                lowestRayTop = Math.min(lowestRayTop, Math.min(
                    Math.min(tops[centerX + distance][centerZ], tops[centerX - distance][centerZ]),
                    Math.min(tops[centerX][centerZ + distance], tops[centerX][centerZ - distance])));
            }
            for (int localY = 0; localY < 16; localY++) {
                int worldY = section.minBlockY() + localY;
                int value = 0;
                if (worldY > centerTop) {
                    value = 15;
                } else if (worldY + radius > centerTop || worldY > lowestRayTop) {
                    // Same five rays, attenuation, bounds and exposure test as
                    // computeSkyLight. The proven-dark case skips all block reads.
                    for (Direction direction : paths) {
                        cursor.set(section.minBlockX() + x, worldY, section.minBlockZ() + z);
                        int cost = 0;
                        for (int distance = 1; distance <= radius && cost < 15; distance++) {
                            cursor.move(direction);
                            if (!EndlessLogicalHeights.contains(cursor.getY())) break;
                            BlockState state = level.getBlockState(cursor);
                            cost += Math.max(1, state.getLightBlock(level, cursor));
                            if (cost >= 15) break;
                            if (cursor.getY() > tops[cursor.getX() - minX][cursor.getZ() - minZ]) {
                                value = Math.max(value, 15 - cost);
                                break;
                            }
                        }
                    }
                }
                result.set(x, localY, z, value);
                skyLight.put(new BlockKey(section.minBlockX() + x, worldY, section.minBlockZ() + z), value);
            }
        }
        return result;
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
            BlockKey key = BlockKey.of(pos);
            Byte cached = blockLight.get(key);
            if (cached != null) {
                return Byte.toUnsignedInt(cached);
            }
            int value = computeBlockLight(pos);
            blockLight.put(key, (byte) value);
            return value;
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
        invalidateHeightColumn(key);
        invalidateBlockLightPage(pos);
        skyLight.clear();
    }

    public synchronized List<Integer> loadedPageYs(int chunkX, int chunkZ) {
        SparseVerticalColumn<LevelChunkSection> column = columns.get(ChunkPos.asLong(chunkX, chunkZ));
        return column == null ? List.of() : column.pageYs();
    }

    /** All allocated pages in this horizontal chunk, including persisted/evicted pages. */
    public synchronized List<Integer> knownPageYs(int chunkX, int chunkZ) {
        java.util.TreeSet<Integer> pages = new java.util.TreeSet<>(loadedPageYs(chunkX, chunkZ));
        if (disk != null) pages.addAll(disk.pageYs(chunkX, chunkZ));
        return List.copyOf(pages);
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
        invalidateHeightColumn(key);
        evictColumnLightCaches(chunkX, chunkZ);
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

    /**
     * Solve block light only in the finite neighborhood that can influence the
     * requested position. Vanilla block light has a maximum value of 15 and
     * loses at least one level per step, so sources farther than 14 Manhattan
     * blocks cannot contribute. Only the requested target is globally cached:
     * other positions in this target-centered solve can miss sources outside
     * the solve boundary and therefore are not complete answers for themselves.
     */
    private int computeBlockLight(BlockPos target) {
        BlockKey targetKey = BlockKey.of(target);
        Map<BlockKey, Byte> local = new HashMap<>();
        ArrayDeque<LightNode> queue = new ArrayDeque<>();

        for (int dx = -BLOCK_LIGHT_SOURCE_RADIUS; dx <= BLOCK_LIGHT_SOURCE_RADIUS; dx++) {
            int remainingX = BLOCK_LIGHT_SOURCE_RADIUS - Math.abs(dx);
            for (int dy = -remainingX; dy <= remainingX; dy++) {
                int remaining = remainingX - Math.abs(dy);
                for (int dz = -remaining; dz <= remaining; dz++) {
                    int y = target.getY() + dy;
                    if (!EndlessLogicalHeights.contains(y)) {
                        continue;
                    }
                    BlockPos sourcePos = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
                    BlockState sourceState = level.getBlockState(sourcePos);
                    int emission = sourceState.getLightEmission();
                    if (emission <= 0) {
                        continue;
                    }
                    BlockKey source = BlockKey.of(sourcePos);
                    int old = Byte.toUnsignedInt(local.getOrDefault(source, (byte) 0));
                    if (emission > old) {
                        local.put(source, (byte) emission);
                        queue.addLast(new LightNode(source, emission));
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            LightNode node = queue.removeFirst();
            int currentStored = Byte.toUnsignedInt(local.getOrDefault(node.pos, (byte) 0));
            if (node.light < currentStored || node.light <= 1) {
                continue;
            }
            BlockPos currentPos = node.pos.toBlockPos();
            BlockState currentState = level.getBlockState(currentPos);
            for (Direction direction : Direction.values()) {
                BlockKey next = node.pos.relative(direction);
                if (manhattanDistance(next, targetKey) > BLOCK_LIGHT_SOURCE_RADIUS
                    || !EndlessLogicalHeights.contains(next.y)) {
                    continue;
                }
                BlockPos nextPos = next.toBlockPos();
                BlockState nextState = level.getBlockState(nextPos);
                int attenuation = Math.max(1, nextState.getLightBlock(level, nextPos));
                int propagated = node.light - attenuation;
                if (propagated <= 0 || lightFacesOcclude(currentState, currentPos, nextState, nextPos, direction)) {
                    continue;
                }
                int old = Byte.toUnsignedInt(local.getOrDefault(next, (byte) 0));
                if (propagated > old) {
                    local.put(next, (byte) propagated);
                    queue.addLast(new LightNode(next, propagated));
                }
            }
        }

        return Byte.toUnsignedInt(local.getOrDefault(targetKey, (byte) 0));
    }

    /** Match vanilla LightEngine's two-face light-occlusion test. */
    private boolean lightFacesOcclude(
        BlockState fromState,
        BlockPos fromPos,
        BlockState toState,
        BlockPos toPos,
        Direction direction
    ) {
        VoxelShape from = lightOcclusionShape(fromState, fromPos, direction);
        VoxelShape to = lightOcclusionShape(toState, toPos, direction.getOpposite());
        return Shapes.faceShapeOccludes(from, to);
    }

    private VoxelShape lightOcclusionShape(BlockState state, BlockPos pos, Direction direction) {
        if (!state.canOcclude() || !state.useShapeForLightOcclusion()) {
            return Shapes.empty();
        }
        return state.getFaceOcclusionShape(level, pos, direction);
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
        return y > skyOcclusionTop(x, z);
    }

    private int skyOcclusionTop(int x, int z) {
        int extended = getExtendedHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int extendedTop = extended == Integer.MIN_VALUE ? Integer.MIN_VALUE : extended - 1;
        LevelChunk core = level.getChunk(x >> 4, z >> 4);
        int coreTop = core.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        return Math.max(coreTop, extendedTop);
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
                    invalidateHeightColumn(key);
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
        // Only the authoritative server owns persistence dirtiness and snapshot
        // revisions. Client prediction/block-update writes must not advance the
        // same revision namespace or a later authoritative page can look stale
        // and be discarded purely because packet timing differed.
        if (disk != null) {
            dirtyPages.add(pos);
            revisions.put(pos, nextRevision++);
        }
    }

    private void invalidateForBlockChange(BlockPos pos) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        int local = (pos.getX() & 15) | ((pos.getZ() & 15) << 4);
        heightCache.keySet().removeIf(heightKey -> heightKey.chunkKey == key && heightKey.localColumn == local);
        invalidateBlockLightAround(pos);
        // Sky exposure depends on the highest sparse block in a column and can
        // therefore change arbitrarily far below a modified high-Y block.
        skyLight.clear();
    }

    private void invalidateHeightColumn(long key) {
        heightCache.keySet().removeIf(heightKey -> heightKey.chunkKey == key);
    }

    private void invalidateBlockLightAround(BlockPos pos) {
        BlockKey center = BlockKey.of(pos);
        for (int dx = -BLOCK_LIGHT_INVALIDATION_RADIUS; dx <= BLOCK_LIGHT_INVALIDATION_RADIUS; dx++) {
            int remainingX = BLOCK_LIGHT_INVALIDATION_RADIUS - Math.abs(dx);
            for (int dy = -remainingX; dy <= remainingX; dy++) {
                int remaining = remainingX - Math.abs(dy);
                for (int dz = -remaining; dz <= remaining; dz++) {
                    blockLight.remove(new BlockKey(center.x + dx, center.y + dy, center.z + dz));
                }
            }
        }
    }

    private void invalidateBlockLightPage(VerticalPagePos pos) {
        int minX = (pos.chunkX() << 4) - BLOCK_LIGHT_INVALIDATION_RADIUS;
        int maxX = (pos.chunkX() << 4) + 15 + BLOCK_LIGHT_INVALIDATION_RADIUS;
        int minZ = (pos.chunkZ() << 4) - BLOCK_LIGHT_INVALIDATION_RADIUS;
        int maxZ = (pos.chunkZ() << 4) + 15 + BLOCK_LIGHT_INVALIDATION_RADIUS;
        int minY = VerticalPageLayout.pageMinBlockY(pos.pageY()) - BLOCK_LIGHT_INVALIDATION_RADIUS;
        int maxY = VerticalPageLayout.pageMaxBlockY(pos.pageY()) + BLOCK_LIGHT_INVALIDATION_RADIUS;
        blockLight.keySet().removeIf(key -> key.x >= minX && key.x <= maxX
            && key.y >= minY && key.y <= maxY
            && key.z >= minZ && key.z <= maxZ);
    }

    private void evictColumnLightCaches(int chunkX, int chunkZ) {
        blockLight.keySet().removeIf(key -> (key.x >> 4) == chunkX && (key.z >> 4) == chunkZ);
        skyLight.keySet().removeIf(key -> (key.x >> 4) == chunkX && (key.z >> 4) == chunkZ);
    }

    private static int manhattanDistance(BlockKey a, BlockKey b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
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
