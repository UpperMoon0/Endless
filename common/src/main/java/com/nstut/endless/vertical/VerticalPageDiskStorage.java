package com.nstut.endless.vertical;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Dedicated compressed-NBT persistence for extended vertical pages. */
public final class VerticalPageDiskStorage implements VerticalPagePersistence<LevelChunkSection> {
    private static final int FORMAT_VERSION = 1;

    private final ServerLevel level;
    private final Path root;
    private final Map<Long, List<Integer>> pageIndex = new HashMap<>();

    public VerticalPageDiskStorage(ServerLevel level) {
        this.level = level;
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        this.root = DimensionType.getStorageFolder(level.dimension(), worldRoot)
            .resolve("endless")
            .resolve("vertical");
    }

    @Override
    public synchronized Optional<VerticalPage<LevelChunkSection>> load(VerticalPagePos pos) throws IOException {
        Path file = file(pos);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        CompoundTag rootTag = NbtIo.readCompressed(file.toFile());
        int version = rootTag.getInt("FormatVersion");
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported Endless vertical-page format " + version + " at " + file);
        }
        if (rootTag.getInt("ChunkX") != pos.chunkX()
            || rootTag.getInt("ChunkZ") != pos.chunkZ()
            || rootTag.getInt("PageY") != pos.pageY()) {
            throw new IOException("Vertical page coordinate mismatch at " + file);
        }

        VerticalPage<LevelChunkSection> page = new VerticalPage<>(pos.pageY());
        ListTag sections = rootTag.getList("Sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag sectionTag = sections.getCompound(i);
            int localY = sectionTag.getInt("LocalY");
            VerticalPageLayout.checkLocalSectionY(localY);
            byte[] payload = sectionTag.getByteArray("Data");
            if (payload.length == 0) {
                throw new IOException("Empty section payload for local Y " + localY + " at " + file);
            }
            if (page.getLocalSection(localY) != null) {
                throw new IOException("Duplicate section local Y " + localY + " at " + file);
            }
            try {
                page.putLocalSection(localY, VerticalPageCodec.decodeSection(level, payload));
            } catch (RuntimeException e) {
                throw new IOException("Invalid section payload for local Y " + localY + " at " + file, e);
            }
        }
        rememberPage(pos, !page.isEmpty());
        return page.isEmpty() ? Optional.empty() : Optional.of(page);
    }

    @Override
    public synchronized void save(VerticalPagePos pos, VerticalPage<LevelChunkSection> page) throws IOException {
        if (page.pageY() != pos.pageY()) {
            throw new IllegalArgumentException("Page Y does not match persistence key");
        }
        if (page.isEmpty()) {
            delete(pos);
            return;
        }

        CompoundTag rootTag = new CompoundTag();
        rootTag.putInt("FormatVersion", FORMAT_VERSION);
        rootTag.putInt("ChunkX", pos.chunkX());
        rootTag.putInt("ChunkZ", pos.chunkZ());
        rootTag.putInt("PageY", pos.pageY());

        ListTag sections = new ListTag();
        page.forEachOccupiedSection((absoluteSectionY, section) -> {
            CompoundTag sectionTag = new CompoundTag();
            sectionTag.putInt("LocalY", VerticalPageLayout.localSectionY(absoluteSectionY));
            sectionTag.putByteArray("Data", VerticalPageCodec.encodeSection(section));
            sections.add(sectionTag);
        });
        rootTag.put("Sections", sections);

        Path file = file(pos);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(rootTag, temp.toFile());
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        rememberPage(pos, true);
    }

    @Override
    public synchronized void delete(VerticalPagePos pos) throws IOException {
        Files.deleteIfExists(file(pos));
        rememberPage(pos, false);
    }

    public synchronized boolean exists(VerticalPagePos pos) {
        return Files.isRegularFile(file(pos));
    }

    /**
     * Sparse page index for one horizontal chunk. The first lookup discovers
     * existing page directories, then saves/deletes maintain the cached index.
     */
    public synchronized List<Integer> pageYs(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        List<Integer> cached = pageIndex.get(key);
        if (cached != null) {
            return cached;
        }

        ArrayList<Integer> found = new ArrayList<>();
        if (Files.isDirectory(root)) {
            String chunkFile = "c." + chunkX + "." + chunkZ + ".nbt";
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    String name = dir.getFileName().toString();
                    if (!name.startsWith("p.")) {
                        return;
                    }
                    try {
                        int pageY = Integer.parseInt(name.substring(2));
                        if (Files.isRegularFile(dir.resolve(chunkFile))) {
                            found.add(pageY);
                        }
                    } catch (NumberFormatException ignored) {
                        // Ignore foreign directories under Endless storage.
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException("Failed to index Endless vertical pages", e);
            }
        }
        Collections.sort(found);
        List<Integer> result = List.copyOf(found);
        pageIndex.put(key, result);
        return result;
    }

    private void rememberPage(VerticalPagePos pos, boolean present) {
        long key = ChunkPos.asLong(pos.chunkX(), pos.chunkZ());
        List<Integer> existing = pageIndex.get(key);
        if (existing == null) {
            return;
        }
        ArrayList<Integer> updated = new ArrayList<>(existing);
        if (present) {
            if (!updated.contains(pos.pageY())) {
                updated.add(pos.pageY());
                Collections.sort(updated);
            }
        } else {
            updated.remove(Integer.valueOf(pos.pageY()));
        }
        pageIndex.put(key, List.copyOf(updated));
    }

    private Path file(VerticalPagePos pos) {
        return root.resolve("p." + pos.pageY())
            .resolve("c." + pos.chunkX() + "." + pos.chunkZ() + ".nbt");
    }
}
