package com.nstut.endless.compat.create;

import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent per-dimension allocator for Create generator network IDs outside
 * Endless' dense core. See the 1.20.1 implementation for the namespace proof.
 */
public final class CreateKineticIdData extends SavedData {
    public static final String DATA_NAME = "endless_create_kinetic_ids";

    // Level.isInWorldBoundsHorizontal rejects X >= 30,000,000 on both targets.
    // BlockPos packs X into the top 26 bits; all 38 remaining bits are free.
    private static final int RESERVED_X = 30_000_000;
    private static final int SEQUENCE_BITS = 38;
    private static final long MAX_SEQUENCE_EXCLUSIVE = 1L << SEQUENCE_BITS;
    private static final long NAMESPACE_PREFIX = (long) RESERVED_X << SEQUENCE_BITS;
    // Version 2 also wrote provisional sparse followers without a role flag.
    // Those records cannot be distinguished from generator ownership.
    private static final int NAMESPACE_VERSION = 3;

    private final Map<PositionKey, Long> ids = new HashMap<>();
    private final Map<Long, PositionKey> positionsById = new HashMap<>();
    // A retained generator network must never become a provisional network
    // after a different kinetic block is restored at the same position.
    private final Map<PositionKey, Long> followerIds = new HashMap<>();
    private final Set<Long> provisionalIds = new HashSet<>();
    private long nextSequence;

    public CreateKineticIdData() {
        verifyNamespaceContract();
    }

    public static CreateKineticIdData load(CompoundTag tag) {
        CreateKineticIdData data = new CreateKineticIdData();
        if (!tag.contains("Namespace", Tag.TAG_INT) || tag.getInt("Namespace") != NAMESPACE_VERSION) {
            throw new IllegalArgumentException("Unsupported pre-release Endless/Create kinetic namespace; use the matching draft build or migrate offline");
        }
        if (!tag.contains("NextSequence", Tag.TAG_LONG) || !tag.contains("Entries", Tag.TAG_LIST)) {
            throw new IllegalArgumentException("Missing or invalid Endless/Create kinetic allocator fields");
        }
        ListTag rawEntries = (ListTag) tag.get("Entries");
        if (!rawEntries.isEmpty() && rawEntries.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid Endless/Create kinetic allocator entry list");
        }
        long persistedNext = tag.getLong("NextSequence");
        if (persistedNext < 0 || persistedNext > MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalArgumentException("Invalid Endless/Create kinetic next sequence " + persistedNext);
        }

        ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
        Set<Long> usedIds = new HashSet<>();
        long highestSequence = -1;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.contains("X", Tag.TAG_INT) || !entry.contains("Y", Tag.TAG_INT)
                || !entry.contains("Z", Tag.TAG_INT) || !entry.contains("Id", Tag.TAG_LONG)
                || (!EndlessHeights.isOutsideDenseBuildHeight(entry.getInt("Y")) && !entry.getBoolean("Follower"))) {
                throw new IllegalArgumentException("Invalid Endless/Create kinetic allocator position record");
            }
            PositionKey key = new PositionKey(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
            long id = entry.getLong("Id");
            long sequence = sequenceForSyntheticId(id);
            boolean follower = entry.getBoolean("Follower");
            Long previous = (follower ? data.followerIds : data.ids).putIfAbsent(key, id);
            if (previous != null && previous.longValue() != id) {
                throw new IllegalArgumentException("Conflicting Endless/Create kinetic IDs for " + key);
            }
            if (!usedIds.add(id) && (previous == null || previous.longValue() != id)) {
                throw new IllegalArgumentException("Duplicate Endless/Create kinetic ID " + id);
            }
            data.positionsById.put(id, key);
            if (follower) data.provisionalIds.add(id);
            highestSequence = Math.max(highestSequence, sequence);
        }

        data.nextSequence = Math.max(persistedNext, highestSequence + 1);
        if (data.nextSequence > MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalArgumentException("Endless/Create kinetic ID namespace exhausted in persisted data");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Namespace", NAMESPACE_VERSION);
        tag.putLong("NextSequence", nextSequence);
        ListTag entries = new ListTag();
        saveEntries(entries, ids, false);
        saveEntries(entries, followerIds, true);
        tag.put("Entries", entries);
        return tag;
    }

    private static void saveEntries(ListTag entries, Map<PositionKey, Long> mappings, boolean follower) {
        for (Map.Entry<PositionKey, Long> mapping : mappings.entrySet()) {
            CompoundTag entry = new CompoundTag();
            PositionKey key = mapping.getKey();
            entry.putInt("X", key.x()); entry.putInt("Y", key.y()); entry.putInt("Z", key.z());
            entry.putLong("Id", mapping.getValue());
            if (follower) entry.putBoolean("Follower", true);
            entries.add(entry);
        }
    }

    public static long idFor(ServerLevel level, BlockPos pos) {
        if (!EndlessHeights.isOutsideDenseBuildHeight(pos.getY())) {
            throw new IllegalArgumentException("Create synthetic kinetic ID requested inside Endless dense core: " + pos);
        }
        return dataFor(level).idForPosition(pos);
    }

    /** Unresolved followers may cross the dense core on their way to a sparse root. */
    public static long idForUnresolvedFollower(ServerLevel level, BlockPos pos) {
        return dataFor(level).idForFollowerPosition(pos);
    }

    /** Only saved admissions from the same legacy root consume unloaded totals. */
    public static boolean replacesLegacyId(ServerLevel level, long synthetic, long legacy) {
        CreateKineticIdData data = dataFor(level);
        PositionKey root = data.positionsById.get(synthetic);
        return !data.provisionalIds.contains(synthetic) && root != null
            && new BlockPos(root.x(), root.y(), root.z()).asLong() == legacy;
    }

    /** Full-position ownership, including interrupted chunk saves. */
    public static boolean belongsTo(ServerLevel level, long id, BlockPos pos) {
        CreateKineticIdData data = dataFor(level);
        return !data.provisionalIds.contains(id) && PositionKey.of(pos).equals(data.positionsById.get(id));
    }

    private static CreateKineticIdData dataFor(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dataFile = DimensionType.getStorageFolder(level.dimension(), worldRoot)
            .resolve("data").resolve(DATA_NAME + ".dat");
        return getOrCreate(level.getDataStorage(), dataFile);
    }

    /**
     * The fallback supplier runs OUTSIDE DimensionDataStorage's catch-and-return-null
     * boundary. Existing/unreadable storage must never become a fresh ID namespace.
     * Package visibility also lets disk-backed tests exercise the actual boundary.
     */
    static CreateKineticIdData getOrCreate(DimensionDataStorage storage, Path dataFile) {
        return storage.computeIfAbsent(new SavedData.Factory<>(
            () -> newAllocatorOnlyIfAbsent(dataFile),
            (tag, registries) -> load(tag), null), DATA_NAME);
    }

    private static CreateKineticIdData newAllocatorOnlyIfAbsent(Path dataFile) {
        // notExists distinguishes a confirmed missing file from inaccessible/unknown
        // status. Do not replace the corrupt file: the operator can restore a backup.
        if (!Files.notExists(dataFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Refusing to reset unreadable Endless/Create kinetic allocator: "
                + dataFile + "; restore this dimension's allocator from a verified backup");
        }
        return new CreateKineticIdData();
    }

    synchronized long idForPosition(BlockPos pos) {
        return allocate(pos, ids, false);
    }

    synchronized long idForFollowerPosition(BlockPos pos) {
        return allocate(pos, followerIds, true);
    }

    private long allocate(BlockPos pos, Map<PositionKey, Long> mappings, boolean follower) {
        PositionKey key = PositionKey.of(pos);
        Long existing = mappings.get(key);
        if (existing != null) {
            return existing;
        }
        if (nextSequence >= MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalStateException("Endless/Create kinetic ID namespace exhausted");
        }

        long id = syntheticIdForSequence(nextSequence++);
        mappings.put(key, id);
        positionsById.put(id, key);
        if (follower) provisionalIds.add(id);
        setDirty();
        return id;
    }

    static long syntheticIdForSequence(long sequence) {
        if (sequence < 0 || sequence >= MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalArgumentException("Synthetic Create kinetic sequence out of range: " + sequence);
        }
        return NAMESPACE_PREFIX | sequence;
    }

    public static boolean isSyntheticId(long id) {
        return (id >>> SEQUENCE_BITS) == RESERVED_X;
    }

    private static long sequenceForSyntheticId(long id) {
        if (!isSyntheticId(id)) {
            throw new IllegalArgumentException("Invalid Endless/Create synthetic kinetic ID " + id);
        }
        return id & (MAX_SEQUENCE_EXCLUSIVE - 1);
    }

    private static void verifyNamespaceContract() {
        BlockPos first = BlockPos.of(NAMESPACE_PREFIX);
        BlockPos last = BlockPos.of(NAMESPACE_PREFIX | (MAX_SEQUENCE_EXCLUSIVE - 1));
        if (BlockPos.PACKED_Y_LENGTH != 12 || first.getX() != RESERVED_X || last.getX() != RESERVED_X) {
            throw new IllegalStateException("Endless/Create kinetic namespace no longer matches BlockPos packing");
        }
    }

    private record PositionKey(int x, int y, int z) {
        static PositionKey of(BlockPos pos) {
            return new PositionKey(pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
