package com.nstut.endless.compat.create;

import com.nstut.endless.config.EndlessConfig;
import com.nstut.endless.heights.EndlessHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent per-dimension allocator for Create generator network IDs outside
 * Endless' dense core.
 *
 * <p>Vanilla 1.20.1 packs Y into the low 12 bits of BlockPos longs. Endless'
 * persisted dense envelope is [-2032, 2032), so packed Y codes 2032..2063 are
 * unreachable by every dense generator: positive dense Y occupies 0..2031 and
 * negative dense Y occupies 2064..4095. We reserve exactly that 32-code gap.
 * The other 52 packed bits plus five reserved-Y selector bits provide 2^57
 * collision-free synthetic IDs. Exhaustion fails closed instead of wrapping.</p>
 *
 * <p>The mapping is keyed by the full uncompressed world position rather than
 * stored in Create block-entity NBT. That matters because schematics and moving
 * contraptions copy block-entity NBT; a position-keyed allocator cannot clone
 * an identity into a different world position.</p>
 */
public final class CreateKineticIdData extends SavedData {
    public static final String DATA_NAME = "endless_create_kinetic_ids";

    private static final int RESERVED_Y_FIRST = EndlessConfig.DENSE_MAX_BUILD_HEIGHT;
    private static final int RESERVED_Y_COUNT = 32;
    private static final int RESERVED_SLOT_BITS = 5;
    private static final long MAX_SEQUENCE_EXCLUSIVE = 1L << 57;
    private static final long PACKED_Y_MASK = (1L << BlockPos.PACKED_Y_LENGTH) - 1L;

    private final Map<PositionKey, Long> ids = new HashMap<>();
    private long nextSequence;

    public CreateKineticIdData() {
        verifyNamespaceContract();
    }

    public static CreateKineticIdData load(CompoundTag tag) {
        CreateKineticIdData data = new CreateKineticIdData();
        long persistedNext = tag.getLong("NextSequence");
        if (persistedNext < 0 || persistedNext > MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalArgumentException("Invalid Endless/Create kinetic next sequence " + persistedNext);
        }

        ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
        Set<Long> usedIds = new HashSet<>();
        long highestSequence = -1;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            PositionKey key = new PositionKey(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
            long id = entry.getLong("Id");
            long sequence = sequenceForSyntheticId(id);
            Long previous = data.ids.putIfAbsent(key, id);
            if (previous != null && previous.longValue() != id) {
                throw new IllegalArgumentException("Conflicting Endless/Create kinetic IDs for " + key);
            }
            if (!usedIds.add(id) && (previous == null || previous.longValue() != id)) {
                throw new IllegalArgumentException("Duplicate Endless/Create kinetic ID " + id);
            }
            highestSequence = Math.max(highestSequence, sequence);
        }

        data.nextSequence = Math.max(persistedNext, highestSequence + 1);
        if (data.nextSequence > MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalArgumentException("Endless/Create kinetic ID namespace exhausted in persisted data");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("NextSequence", nextSequence);
        ListTag entries = new ListTag();
        for (Map.Entry<PositionKey, Long> mapping : ids.entrySet()) {
            CompoundTag entry = new CompoundTag();
            PositionKey key = mapping.getKey();
            entry.putInt("X", key.x());
            entry.putInt("Y", key.y());
            entry.putInt("Z", key.z());
            entry.putLong("Id", mapping.getValue());
            entries.add(entry);
        }
        tag.put("Entries", entries);
        return tag;
    }

    public static long idFor(ServerLevel level, BlockPos pos) {
        if (!EndlessHeights.isOutsideDenseBuildHeight(pos.getY())) {
            throw new IllegalArgumentException("Create synthetic kinetic ID requested inside Endless dense core: " + pos);
        }
        CreateKineticIdData data = level.getDataStorage()
            .computeIfAbsent(CreateKineticIdData::load, CreateKineticIdData::new, DATA_NAME);
        return data.idForPosition(pos);
    }

    private synchronized long idForPosition(BlockPos pos) {
        PositionKey key = PositionKey.of(pos);
        Long existing = ids.get(key);
        if (existing != null) {
            return existing;
        }
        if (nextSequence >= MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalStateException("Endless/Create kinetic ID namespace exhausted");
        }

        long id = syntheticIdForSequence(nextSequence++);
        ids.put(key, id);
        setDirty();
        return id;
    }

    static long syntheticIdForSequence(long sequence) {
        if (sequence < 0 || sequence >= MAX_SEQUENCE_EXCLUSIVE) {
            throw new IllegalArgumentException("Synthetic Create kinetic sequence out of range: " + sequence);
        }
        long upper52 = sequence >>> RESERVED_SLOT_BITS;
        long yCode = RESERVED_Y_FIRST + (sequence & (RESERVED_Y_COUNT - 1L));
        return (upper52 << BlockPos.PACKED_Y_LENGTH) | yCode;
    }

    private static long sequenceForSyntheticId(long id) {
        long yCode = id & PACKED_Y_MASK;
        if (yCode < RESERVED_Y_FIRST || yCode >= RESERVED_Y_FIRST + RESERVED_Y_COUNT) {
            throw new IllegalArgumentException("Invalid Endless/Create synthetic kinetic ID " + id);
        }
        long upper52 = id >>> BlockPos.PACKED_Y_LENGTH;
        return (upper52 << RESERVED_SLOT_BITS) | (yCode - RESERVED_Y_FIRST);
    }

    private static void verifyNamespaceContract() {
        if (BlockPos.PACKED_Y_LENGTH != 12
            || EndlessConfig.DENSE_MIN_BUILD_HEIGHT != -2032
            || EndlessConfig.DENSE_MAX_BUILD_HEIGHT != 2032
            || RESERVED_Y_FIRST + RESERVED_Y_COUNT != 2064) {
            throw new IllegalStateException("Endless/Create kinetic ID namespace no longer matches BlockPos/dense-core layout");
        }
    }

    private record PositionKey(int x, int y, int z) {
        static PositionKey of(BlockPos pos) {
            return new PositionKey(pos.getX(), pos.getY(), pos.getZ());
        }
    }
}