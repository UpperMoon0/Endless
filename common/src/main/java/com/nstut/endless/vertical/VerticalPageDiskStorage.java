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
import java.util.Optional;

/**
 * Dedicated compressed-NBT persistence for extended vertical pages.
 *
 * <p>Files are intentionally outside vanilla region/chunk NBT so a vanilla
 * serializer can never truncate or drop their absolute section Y.</p>
 */
public final class VerticalPageDiskStorage implements VerticalPagePersistence<LevelChunkSection> {
    private static final int FORMAT_VERSION = 1;

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
    public Optional<VerticalPage<LevelChunkSection>> load(VerticalPagePos pos) throws IOException {
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
    public void save(VerticalPagePos pos, VerticalPage<LevelChunkSection> page) throws IOException {
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
            Files.move(temp, file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(VerticalPagePos pos) throws IOException {
        Files.deleteIfExists(file(pos));
    }

    public boolean exists(VerticalPagePos pos) {
        return Files.isRegularFile(file(pos));
    }

    private Path file(VerticalPagePos pos) {
        return root.resolve("p." + pos.pageY())
            .resolve("c." + pos.chunkX() + "." + pos.chunkZ() + ".nbt");
    }
}
