package com.nstut.endless.compat.create;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Renderer-local keys outside the legal packed X namespace, with exact inverse lookup. */
public final class CreateDestructionPositions {
    private final Map<BlockPos, Long> keys = new HashMap<>();
    private final Map<Long, BlockPos> positions = new HashMap<>();
    private long sequence;
    public long key(BlockPos pos) {
        return keys.computeIfAbsent(pos.immutable(), p -> {
            if (sequence == (1L << 38) - 1) throw new IllegalStateException("destruction key space exhausted");
            long key = (30_000_000L << 38) | ++sequence;
            positions.put(key, p);
            return key;
        });
    }
    public long lookup(BlockPos pos) { return keys.getOrDefault(pos, Long.MIN_VALUE); }
    public BlockPos position(long key) {
        BlockPos pos = positions.get(key);
        return pos == null ? BlockPos.of(key) : pos;
    }
    public void remove(BlockPos pos) { Long key = keys.remove(pos); if (key != null) positions.remove(key); }
    public void clear() { keys.clear(); positions.clear(); sequence = 0; }
    public int size() { return keys.size(); }
}
