package com.nstut.endless.testing;

import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;

/** Test-only client-thread evidence that vanilla server acknowledgements settled predictions. */
public final class LivePredictionProbe {
    private static final Map<BlockPos, Integer> ACKNOWLEDGED = new HashMap<>();

    private LivePredictionProbe() {}

    public static void acknowledged(BlockPos pos) {
        if (LiveJoinTest.isArmed()) ACKNOWLEDGED.merge(pos.immutable(), 1, Integer::sum);
    }

    public static int count(BlockPos pos) { return ACKNOWLEDGED.getOrDefault(pos, 0); }
}
