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

    static boolean hasPlayedRegionData(Path worldRoot) throws IOException {
        return !findRegionFiles(worldRoot).isEmpty();
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

            // Vanilla RegionFile is the safest reader here because it handles
            // all supported Anvil compression modes and external .mcc chunks.
            // The file already exists; this code never requests an output
            // stream and therefore performs no chunk writes.
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

    private static EdgeUsage inspectChunk(CompoundTag chunk, boolean inspectBottom, boolean inspectTop) {
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

        // A block entity is also meaningful user/world data even if the
        // section palette is malformed or unexpectedly absent.
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

    private static boolean hasMeaningfulBlockStates(CompoundTag section) {
        if (!section.contains("block_states", Tag.TAG_COMPOUND)) {
            return false;
        }

        CompoundTag blockStates = section.getCompound("block_states");
        if (!blockStates.contains("palette", Tag.TAG_LIST)) {
            // Malformed edge data must never be treated as safely empty.
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
