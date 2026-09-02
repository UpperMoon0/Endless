package com.nstut.endless.vertical;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
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
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Dedicated compressed-NBT persistence for extended vertical pages. */
public final class VerticalPageDiskStorage implements VerticalPagePersistence<LevelChunkSection> {
    private static final int FORMAT_VERSION = 1;
    private static final String PAGE_PREFIX = "p.";
    private static final String PAGE_SUFFIX = ".nbt";

    private final ServerLevel level;
    private final Path root;

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
    }

    @Override
    public synchronized void delete(VerticalPagePos pos) throws IOException {
        Path file = file(pos);
        Files.deleteIfExists(file);
        deleteDirectoryIfEmpty(file.getParent());
    }

    public synchronized boolean exists(VerticalPagePos pos) {
        return Files.isRegularFile(file(pos));
    }

    /**
     * Discover only pages belonging to the requested horizontal chunk.
     *
     * <p>The storage layout is chunk-first ({@code c.x.z/p.y.nbt}), so this is
     * O(pages in this chunk) instead of O(all distinct page Ys in the entire
     * dimension). That keeps height queries stable even in highly fragmented
     * million-block-tall worlds.</p>
     */
    public synchronized List<Integer> pageYs(int chunkX, int chunkZ) {
        Path chunkDir = chunkDirectory(chunkX, chunkZ);
        if (!Files.isDirectory(chunkDir)) {
            return List.of();
        }

        ArrayList<Integer> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(chunkDir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                Integer pageY = parsePageY(path.getFileName().toString());
                if (pageY != null) {
                    found.add(pageY);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to index Endless vertical pages for chunk "
                + chunkX + "," + chunkZ, e);
        }
        Collections.sort(found);
        return List.copyOf(found);
    }

    private Integer parsePageY(String name) {
        if (!name.startsWith(PAGE_PREFIX) || !name.endsWith(PAGE_SUFFIX)) {
            return null;
        }
        String raw = name.substring(PAGE_PREFIX.length(), name.length() - PAGE_SUFFIX.length());
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void deleteDirectoryIfEmpty(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        }
    }

    private Path chunkDirectory(int chunkX, int chunkZ) {
        return root.resolve("c." + chunkX + "." + chunkZ);
    }

    private Path file(VerticalPagePos pos) {
        return chunkDirectory(pos.chunkX(), pos.chunkZ())
            .resolve(PAGE_PREFIX + pos.pageY() + PAGE_SUFFIX);
    }
}
