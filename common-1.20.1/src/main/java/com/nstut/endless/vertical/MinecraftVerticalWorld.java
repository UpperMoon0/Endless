package com.nstut.endless.vertical;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.heights.EndlessHeights;
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
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ChunkStatus;
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
    private final SectionLightCache<DataLayer> sectionBlockLight = new SectionLightCache<>(512);
    private final Map<BlockKey, Integer> skyLight = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockKey, Integer> eldest) {
            return size() > SKY_CACHE_LIMIT;
        }
    };
    private final WeightedCache<VerticalPagePos,VerticalPageSnapshot> snapshots=new WeightedCache<>(16<<20);
    private long nextRevision = 1L;
    private long lightCacheRevision;

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
    public DataLayer copyRenderBlockLight(SectionPos sectionPos) {
        return cachedSectionBlockLight(sectionPos).copy();
    }

    private DataLayer cachedSectionBlockLight(SectionPos section) {
        return sectionBlockLight.get(new SectionLightCache.Key(section.x(),section.y(),section.z()),
            ()->solveSectionBlockLight(section));
    }

    private static BlockState snapshotState(Map<SectionLightCache.Key,PalettedContainer<BlockState>> states,BlockPos pos) {
        var palette=states.get(new SectionLightCache.Key(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4));
        return palette==null ? Blocks.AIR.defaultBlockState() : palette.get(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
    }

    private DataLayer solveSectionBlockLight(SectionPos sectionPos) {
        int minX = sectionPos.minBlockX() - BLOCK_LIGHT_SOURCE_RADIUS;
        int maxX = sectionPos.maxBlockX() + BLOCK_LIGHT_SOURCE_RADIUS;
        int minY = sectionPos.minBlockY() - BLOCK_LIGHT_SOURCE_RADIUS;
        int maxY = sectionPos.maxBlockY() + BLOCK_LIGHT_SOURCE_RADIUS;
        int minZ = sectionPos.minBlockZ() - BLOCK_LIGHT_SOURCE_RADIUS;
        int maxZ = sectionPos.maxBlockZ() + BLOCK_LIGHT_SOURCE_RADIUS;
        Map<BlockKey, Byte> local = new HashMap<>();
        ArrayDeque<LightNode> queue = new ArrayDeque<>();
        Map<SectionLightCache.Key,PalettedContainer<BlockState>> states = new HashMap<>();
        boolean emitters=false;
        for(int cx=minX>>4;cx<=maxX>>4;cx++) for(int cz=minZ>>4;cz<=maxZ>>4;cz++) {
            // Lighting must not synchronously generate a missing neighbor chunk.
            var dense=level.getChunk(cx,cz,ChunkStatus.FULL,false);
            for(int sy=minY>>4;sy<=maxY>>4;sy++) {
                PalettedContainer<BlockState> palette;
                int index=level.getSectionIndexFromSectionY(sy);
                if(dense!=null && index>=0 && index<dense.getSections().length) {
                    palette=dense.getSections()[index].getStates().copy();
                } else {
                    synchronized(this) {
                        var section=getSection(cx,cz,sy,false);
                        if(section==null) continue;
                        palette=section.getStates().copy();
                    }
                }
                states.put(new SectionLightCache.Key(cx,sy,cz),palette);
                if(!palette.maybeHas(state->state.getLightEmission()>0)) continue;
                emitters=true;
                for(int y=Math.max(minY,sy<<4);y<=Math.min(maxY,(sy<<4)+15);y++) {
                    if(!EndlessLogicalHeights.contains(y)) continue;
                    for(int z=Math.max(minZ,cz<<4);z<=Math.min(maxZ,(cz<<4)+15);z++)
                        for(int x=Math.max(minX,cx<<4);x<=Math.min(maxX,(cx<<4)+15);x++) {
                            int emission=palette.get(x&15,y&15,z&15).getLightEmission();
                            if(emission>0) {
                                var key=new BlockKey(x,y,z);
                                local.put(key,(byte)emission);queue.addLast(new LightNode(key,emission));
                            }
                        }
                }
            }
        }
        if(!emitters) return new DataLayer();
        while (!queue.isEmpty()) {
            LightNode node = queue.removeFirst();
            if (node.light <= 1 || node.light < Byte.toUnsignedInt(local.getOrDefault(node.pos, (byte) 0))) continue;
            BlockPos from = node.pos.toBlockPos();
            BlockState fromState = snapshotState(states,from);
            for (Direction direction : Direction.values()) {
                BlockKey next = node.pos.relative(direction);
                if (next.x < minX || next.x > maxX || next.y < minY || next.y > maxY
                    || next.z < minZ || next.z > maxZ || !EndlessLogicalHeights.contains(next.y)) continue;
                BlockPos to = next.toBlockPos();
                BlockState toState = snapshotState(states,to);
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
        }
        return result;
    }

    /** Snapshot skylight with one height query per halo column, not per ray step. */
    public DataLayer copyRenderSkyLight(SectionPos section) {
        while (true) {
            long epoch;
            synchronized (this) { epoch=lightCacheRevision; }
            DataLayer result=solveSectionSkyLight(section);
            synchronized (this) {
                if(epoch!=lightCacheRevision) continue;
                for(int x=0;x<16;x++) for(int z=0;z<16;z++) for(int y=0;y<16;y++)
                    skyLight.put(new BlockKey(section.minBlockX()+x,section.minBlockY()+y,section.minBlockZ()+z),result.get(x,y,z));
                return result;
            }
        }
    }

    // Sky height queries may complete dense chunk admission. Never hold sparse storage while waiting.
    private DataLayer solveSectionSkyLight(SectionPos section) {
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

    public int getBrightness(LightLayer layer, BlockPos pos) {
        if(layer==LightLayer.BLOCK) {
            return cachedSectionBlockLight(SectionPos.of(pos)).get(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
        }
        BlockKey key=BlockKey.of(pos);
        while(true) {
            long epoch;
            synchronized(this) {
                Integer cached=skyLight.get(key);
                if(cached!=null) return cached;
                epoch=lightCacheRevision;
            }
            int value=computeSkyLight(pos);
            synchronized(this) {
                if(epoch!=lightCacheRevision) continue;
                skyLight.put(key,value);return value;
            }
        }
    }

    public synchronized VerticalPageSnapshot snapshot(VerticalPagePos pos, boolean loadFromDisk) {
        long revision=revisions.getOrDefault(pos,0L);
        var cached=snapshots.get(pos);
        if(cached!=null && cached.revision()==revision) return cached;
        var page=getPage(pos,false,loadFromDisk);
        if(page==null || page.isEmpty()) return null;
        var snapshot=VerticalPageSnapshot.fromPage(pos,revision,page);
        snapshots.put(pos,snapshot,snapshot.payloadBytes());
        return snapshot;
    }

    /** Transfers newly generated, private sections to the authoritative world without encoding.
     * Callers must stop mutating the page after this call. Saved or existing pages are refused.
     * This installs block/biome storage only; block entities and scheduled ticks require their native registration. */
    public synchronized void installGeneratedPage(VerticalPagePos pos,VerticalPage<LevelChunkSection> page) {
        if(disk==null || !EndlessLogicalHeights.isActive()) throw new IllegalStateException("Authoritative sparse world required");
        if(pos.pageY()<Math.floorDiv(EndlessHeights.getMinBuildHeight(),512) || pos.pageY()>Math.floorDiv(EndlessHeights.getMaxBuildHeight()-1,512)) throw new IllegalArgumentException("Page outside logical range");
        if(page.pageY()!=pos.pageY() || page.isEmpty()) throw new IllegalArgumentException("Nonempty matching generated page required");
        if(pageExists(pos)) throw new IllegalStateException("Refusing to replace existing generated page "+pos);
        page.forEachOccupiedSection((sy,section)->{
            int y=sy*16;
            if(!EndlessLogicalHeights.contains(y)||!EndlessLogicalHeights.contains(y+15)
                || !EndlessHeights.isOutsideDenseBuildHeight(y)) throw new IllegalArgumentException("Generated section outside sparse range: "+sy);
        });
        long key=ChunkPos.asLong(pos.chunkX(),pos.chunkZ());
        var column=columns.computeIfAbsent(key,ignored->new SparseVerticalColumn<LevelChunkSection>());
        page.forEachOccupiedSection(column::putSection);
        attemptedLoads.add(pos);
        markDirty(pos);
        invalidateHeightColumn(key);invalidateBlockLightPage(pos);skyLight.clear();lightCacheRevision++;
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
        snapshots.remove(pos);
        invalidateHeightColumn(key);
        invalidateBlockLightPage(pos);
        skyLight.clear();lightCacheRevision++;
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

    public synchronized void flushDirtyBudgeted(int limit) {
        flushDirtyBudgeted(limit, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /** Limits how many dirty pages are encoded on the server tick.
     * A single save is atomic and may exceed the target; subsequent pages are deferred.
     * Explicit save/unload/close still persist all revisions. */
    public synchronized void flushDirtyBudgeted(int limit, long maxNanos, long maxEstimatedBytes) {
        if(limit<1 || disk==null || maxNanos<1 || maxEstimatedBytes<1) return;
        long started=System.nanoTime(), estimated=0;
        int count=0;
        for(var pos:new ArrayList<>(dirtyPages)) {
            if(count>0 && System.nanoTime()-started>=maxNanos) break;
            long pageEstimate=estimatedPersistBytes(pos);
            if(count>0 && estimated+pageEstimate>maxEstimatedBytes) break;
            persist(pos);
            estimated+=pageEstimate;
            if(++count>=limit) break;
        }
    }

    /** Lightweight conservative scheduling estimate, not an encoded-size guarantee. */
    private long estimatedPersistBytes(VerticalPagePos pos) {
        var page=getPage(pos,false,false);
        if(page==null || page.isEmpty()) return 4096L;
        long[] sections={0};
        page.forEachOccupiedSection((sectionY,section)->sections[0]++);
        return Math.max(4096L, sections[0]*(64L<<10));
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
        snapshots.removeIf(pos->pos.chunkX()==chunkX&&pos.chunkZ()==chunkZ);
        invalidateHeightColumn(key);
        evictColumnLightCaches(chunkX, chunkZ);
    }

    public synchronized void close() {
        flushDirty();
        columns.clear();
        attemptedLoads.clear();
        dirtyPages.clear();
        revisions.clear();
        snapshots.clear();
        heightCache.clear();
        sectionBlockLight.clear();
        skyLight.clear();lightCacheRevision++;
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
        snapshots.remove(pos);
        // Only the authoritative server owns persistence dirtiness and snapshot
        // revisions. Client prediction/block-update writes must not advance the
        // same revision namespace or a later authoritative page can look stale
        // and be discarded purely because packet timing differed.
        if (disk != null) {
            dirtyPages.add(pos);
            revisions.put(pos, nextRevision++);
        }
    }

    /** Dense heightmaps also participate in sparse sky exposure. */
    public synchronized void invalidateSkyLight() {
        skyLight.clear();lightCacheRevision++;
    }

    private void invalidateForBlockChange(BlockPos pos) {
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        int local = (pos.getX() & 15) | ((pos.getZ() & 15) << 4);
        heightCache.keySet().removeIf(heightKey -> heightKey.chunkKey == key && heightKey.localColumn == local);
        invalidateBlockLightAround(pos);
        // Sky exposure depends on the highest sparse block in a column and can
        // therefore change arbitrarily far below a modified high-Y block.
        skyLight.clear();lightCacheRevision++;
    }

    private void invalidateHeightColumn(long key) {
        heightCache.keySet().removeIf(heightKey -> heightKey.chunkKey == key);
    }

    private void invalidateBlockLightAround(BlockPos pos) {
        int r=BLOCK_LIGHT_INVALIDATION_RADIUS;
        sectionBlockLight.invalidateBox(pos.getX()-r,pos.getY()-r,pos.getZ()-r,pos.getX()+r,pos.getY()+r,pos.getZ()+r);
    }

    /** Dense edits, chunk admission and light section changes affect sparse neighbors. */
    public synchronized void invalidateLighting(BlockPos pos) {
        invalidateForBlockChange(pos);
    }

    public synchronized void invalidateLightingSection(SectionPos section) {
        int r=BLOCK_LIGHT_INVALIDATION_RADIUS;
        sectionBlockLight.invalidateBox(section.minBlockX()-r,section.minBlockY()-r,section.minBlockZ()-r,
            section.maxBlockX()+r,section.maxBlockY()+r,section.maxBlockZ()+r);
        skyLight.clear();lightCacheRevision++;
    }

    private void invalidateBlockLightPage(VerticalPagePos pos) {
        int minX = (pos.chunkX() << 4) - BLOCK_LIGHT_INVALIDATION_RADIUS;
        int maxX = (pos.chunkX() << 4) + 15 + BLOCK_LIGHT_INVALIDATION_RADIUS;
        int minZ = (pos.chunkZ() << 4) - BLOCK_LIGHT_INVALIDATION_RADIUS;
        int maxZ = (pos.chunkZ() << 4) + 15 + BLOCK_LIGHT_INVALIDATION_RADIUS;
        int minY = VerticalPageLayout.pageMinBlockY(pos.pageY()) - BLOCK_LIGHT_INVALIDATION_RADIUS;
        int maxY = VerticalPageLayout.pageMaxBlockY(pos.pageY()) + BLOCK_LIGHT_INVALIDATION_RADIUS;
        sectionBlockLight.invalidateBox(minX,minY,minZ,maxX,maxY,maxZ);
    }

    private void evictColumnLightCaches(int chunkX, int chunkZ) {
        int r=BLOCK_LIGHT_INVALIDATION_RADIUS;
        sectionBlockLight.invalidateBox((chunkX<<4)-r,-8000000,(chunkZ<<4)-r,(chunkX<<4)+15+r,7999999,(chunkZ<<4)+15+r);
        skyLight.keySet().removeIf(key -> (key.x >> 4) == chunkX && (key.z >> 4) == chunkZ);
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
