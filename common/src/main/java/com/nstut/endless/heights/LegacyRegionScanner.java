package com.nstut.endless.heights;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One-time, fail-closed inspection of legacy Anvil region files. */
final class LegacyRegionScanner {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int BOTTOM_EDGE_SECTION_Y = -128;
    private static final int TOP_EDGE_SECTION_Y = 127;

    private LegacyRegionScanner() {
    }

    record EdgeUsage(boolean bottomHasMeaningfulData, boolean topHasMeaningfulData) {
    }

    /**
     * Evidence that the candidate range does not match the layout with which
     * the chunk was saved. Sentinel values are -1 when the corresponding flag
     * is false.
     */
    record WorldEvidence(
        boolean meaningfulDataOutsideCandidate,
        int outsideSectionY,
        boolean heightmapLayoutMismatch,
        int savedHeightmapLongs,
        int expectedHeightmapLongs
    ) {
        static WorldEvidence none(int expectedHeightmapLongs) {
            return new WorldEvidence(false, -1, false, -1, expectedHeightmapLongs);
        }

        boolean blocksMigration() {
            return meaningfulDataOutsideCandidate || heightmapLayoutMismatch;
        }
    }

    static boolean hasPlayedRegionData(Path worldRoot) throws IOException {
        return !findRegionFiles(worldRoot).isEmpty();
    }

    /**
     * Inspect every saved chunk in every dimension before accepting a
     * pre-v0.4 migration candidate.
     *
     * <p>The candidate range is where v0.4 would construct its section array.
     * Any meaningful saved section or block entity outside that range would be
     * skipped by vanilla ChunkSerializer and therefore makes migration unsafe.
     * Heightmaps are compared against the normalized raw legacy span (not the
     * guard-clamped candidate), because their packed-array length is a useful
     * historical clue: for example a 64-long 1.20.1 heightmap is incompatible
     * with a current narrow config even when no outside section happens to
     * contain non-air blocks.</p>
     */
    static WorldEvidence scanWorldAgainstCandidate(
        Path worldRoot,
        int candidateMin,
        int candidateMax,
        int expectedLegacyHeight
    ) throws IOException {
        int expectedHeightmapLongs = heightmapStorageLongs(expectedLegacyHeight);

        for (Path regionPath : findRegionFiles(worldRoot)) {
            Matcher matcher = REGION_FILE.matcher(regionPath.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }

            final int regionX;
            final int regionZ;
            try {
                regionX = Integer.parseInt(matcher.group(1));
                regionZ = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid region filename: " + regionPath, e);
            }

            try (RegionFile region = new RegionFile(regionPath, regionPath.getParent(), false)) {
                for (int localX = 0; localX < 32; localX++) {
                    for (int localZ = 0; localZ < 32; localZ++) {
                        ChunkPos pos = new ChunkPos((regionX << 5) + localX, (regionZ << 5) + localZ);
                        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                            if (in == null) {
                                continue;
                            }
                            CompoundTag chunk = NbtIo.read(in);
                            if (chunk == null) {
                                throw new IOException("Chunk " + pos + " in " + regionPath + " had no readable NBT root");
                            }

                            WorldEvidence evidence = inspectChunkAgainstCandidate(
                                chunk, candidateMin, candidateMax, expectedLegacyHeight);
                            if (evidence.blocksMigration()) {
                                return evidence;
                            }
                        } catch (RuntimeException e) {
                            throw new IOException("Failed to inspect chunk " + pos + " in " + regionPath, e);
                        }
                    }
                }
            }
        }

        return WorldEvidence.none(expectedHeightmapLongs);
    }

    static EdgeUsage scanEdgeSections(Path worldRoot, boolean inspectBottom, boolean inspectTop) throws IOException {
        boolean bottom = false;
        boolean top = false;

        for (Path regionPath : findRegionFiles(worldRoot)) {
            Matcher matcher = REGION_FILE.matcher(regionPath.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }

            final int regionX;
            final int regionZ;
            try {
                regionX = Integer.parseInt(matcher.group(1));
                regionZ = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid region filename: " + regionPath, e);
            }

            // Vanilla RegionFile handles all supported Anvil compression modes
            // and external .mcc chunks. The file already exists; this scanner
            // never requests an output stream and therefore performs no chunk
            // writes before migration has been classified.
            try (RegionFile region = new RegionFile(regionPath, regionPath.getParent(), false)) {
                for (int localX = 0; localX < 32; localX++) {
                    for (int localZ = 0; localZ < 32; localZ++) {
                        ChunkPos pos = new ChunkPos((regionX << 5) + localX, (regionZ << 5) + localZ);
                        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                            if (in == null) {
                                continue;
                            }
                            CompoundTag chunk = NbtIo.read(in);
                            if (chunk == null) {
                                throw new IOException("Chunk " + pos + " in " + regionPath + " had no readable NBT root");
                            }

                            EdgeUsage usage = inspectChunk(chunk, inspectBottom && !bottom, inspectTop && !top);
                            bottom |= usage.bottomHasMeaningfulData;
                            top |= usage.topHasMeaningfulData;
                            if ((!inspectBottom || bottom) && (!inspectTop || top)) {
                                return new EdgeUsage(bottom, top);
                            }
                        } catch (RuntimeException e) {
                            throw new IOException("Failed to inspect chunk " + pos + " in " + regionPath, e);
                        }
                    }
                }
            }
        }

        return new EdgeUsage(bottom, top);
    }

    /** Package-private so synthetic-NBT regression tests exercise the real scanner logic. */
    static EdgeUsage inspectChunk(CompoundTag chunk, boolean inspectBottom, boolean inspectTop) {
        boolean bottom = false;
        boolean top = false;

        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int sectionY = section.getByte("Y");
            boolean requestedBottom = inspectBottom && sectionY == BOTTOM_EDGE_SECTION_Y;
            boolean requestedTop = inspectTop && sectionY == TOP_EDGE_SECTION_Y;
            if ((!requestedBottom && !requestedTop) || !hasMeaningfulBlockStates(section)) {
                continue;
            }
            bottom |= requestedBottom;
            top |= requestedTop;
        }

        // A block entity is meaningful user/world data even if the section
        // palette is malformed or unexpectedly absent.
        ListTag blockEntities = chunk.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag blockEntity = blockEntities.getCompound(i);
            int sectionY = Math.floorDiv(blockEntity.getInt("y"), 16);
            if (inspectBottom && sectionY == BOTTOM_EDGE_SECTION_Y) {
                bottom = true;
            }
            if (inspectTop && sectionY == TOP_EDGE_SECTION_Y) {
                top = true;
            }
        }

        return new EdgeUsage(bottom, top);
    }

    /** Package-private synthetic-NBT entry point for changed-config regressions. */
    static WorldEvidence inspectChunkAgainstCandidate(
        CompoundTag chunk,
        int candidateMin,
        int candidateMax,
        int expectedLegacyHeight
    ) {
        if ((candidateMin & 15) != 0 || (candidateMax & 15) != 0 || candidateMin >= candidateMax) {
            throw new IllegalArgumentException("candidate range must be non-empty and section-aligned");
        }

        int minSection = Math.floorDiv(candidateMin, 16);
        int maxSectionExclusive = Math.floorDiv(candidateMax, 16);
        int expectedHeightmapLongs = heightmapStorageLongs(expectedLegacyHeight);

        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            int sectionY = section.getByte("Y");
            if ((sectionY < minSection || sectionY >= maxSectionExclusive)
                && hasMeaningfulBlockStates(section)) {
                return new WorldEvidence(true, sectionY, false, -1, expectedHeightmapLongs);
            }
        }

        ListTag blockEntities = chunk.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag blockEntity = blockEntities.getCompound(i);
            int sectionY = Math.floorDiv(blockEntity.getInt("y"), 16);
            if (sectionY < minSection || sectionY >= maxSectionExclusive) {
                return new WorldEvidence(true, sectionY, false, -1, expectedHeightmapLongs);
            }
        }

        if (chunk.contains("Heightmaps", Tag.TAG_COMPOUND)) {
            CompoundTag heightmaps = chunk.getCompound("Heightmaps");
            for (String key : heightmaps.getAllKeys()) {
                if (!heightmaps.contains(key, Tag.TAG_LONG_ARRAY)) {
                    // A malformed persisted heightmap is not trustworthy
                    // migration evidence. Fail closed rather than guessing.
                    return new WorldEvidence(false, -1, true, -1, expectedHeightmapLongs);
                }
                int savedLongs = heightmaps.getLongArray(key).length;
                if (savedLongs != expectedHeightmapLongs) {
                    return new WorldEvidence(false, -1, true, savedLongs, expectedHeightmapLongs);
                }
            }
        }

        return WorldEvidence.none(expectedHeightmapLongs);
    }

    /**
     * Vanilla 1.20.1 Heightmap uses SimpleBitStorage with
     * bits=ceil(log2(height+1)), 256 values, and floor(64/bits) values/long.
     */
    static int heightmapStorageLongs(int height) {
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        int bits = 32 - Integer.numberOfLeadingZeros(height);
        int valuesPerLong = 64 / bits;
        return (256 + valuesPerLong - 1) / valuesPerLong;
    }

    private static boolean hasMeaningfulBlockStates(CompoundTag section) {
        if (!section.contains("block_states", Tag.TAG_COMPOUND)) {
            return false;
        }

        CompoundTag blockStates = section.getCompound("block_states");
        if (!blockStates.contains("palette", Tag.TAG_LIST)) {
            return true;
        }

        ListTag palette = blockStates.getList("palette", Tag.TAG_COMPOUND);
        if (palette.isEmpty()) {
            return true;
        }

        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompound(i);
            if (!state.contains("Name", Tag.TAG_STRING)) {
                return true;
            }
            String name = state.getString("Name");
            if (!"minecraft:air".equals(name)
                && !"minecraft:cave_air".equals(name)
                && !"minecraft:void_air".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> findRegionFiles(Path worldRoot) throws IOException {
        if (!Files.isDirectory(worldRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(worldRoot)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getParent() != null
                    && path.getParent().getFileName() != null
                    && "region".equals(path.getParent().getFileName().toString()))
                .filter(path -> REGION_FILE.matcher(path.getFileName().toString()).matches())
                .toList();
        }
    }
}
