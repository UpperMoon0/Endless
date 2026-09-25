package com.nstut.endless.vertical;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bounded follow-up scan for sparse pages whose chunk block entities can arrive
 * after the page snapshot itself. Modern vanilla chunk packet paths do not
 * consistently deliver those initial block entities through the standalone
 * block-entity packet handler.
 */
public final class BlockEntityPageRefreshScanner {
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MAX_SCANS = 120;
    private static final Map<VerticalPagePos, PendingPageScan> PENDING = new HashMap<>();

    private BlockEntityPageRefreshScanner() {}

    public static void schedule(VerticalPagePos pos) {
        PENDING.put(pos, new PendingPageScan());
    }

    public static void tick(Minecraft client, Consumer<BlockPos> onDiscoveredBlockEntity) {
        if (client.level == null) {
            PENDING.clear();
            return;
        }
        if (PENDING.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<VerticalPagePos, PendingPageScan>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<VerticalPagePos, PendingPageScan> entry = iterator.next();
            PendingPageScan pending = entry.getValue();
            if (pending.cooldownTicks > 0) {
                pending.cooldownTicks--;
                continue;
            }
            pending.cooldownTicks = SCAN_INTERVAL_TICKS - 1;

            VerticalPagePos pagePos = entry.getKey();
            LevelChunk chunk = client.level.getChunkSource().getChunk(
                pagePos.chunkX(), pagePos.chunkZ(), ChunkStatus.FULL, false);
            if (chunk != null) {
                int minY = VerticalPageLayout.pageMinBlockY(pagePos.pageY());
                int maxY = VerticalPageLayout.pageMaxBlockY(pagePos.pageY());
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getY() < minY || pos.getY() > maxY) {
                        continue;
                    }
                    ExactBlockPos exact = new ExactBlockPos(pos.getX(), pos.getY(), pos.getZ());
                    if (pending.seenBlockEntities.add(exact)) {
                        onDiscoveredBlockEntity.accept(pos);
                    }
                }
            }

            pending.scans++;
            if (pending.scans >= MAX_SCANS) {
                iterator.remove();
            }
        }
    }

    public static void reset() {
        PENDING.clear();
    }

    private static final class PendingPageScan {
        private int cooldownTicks;
        private int scans;
        private final Set<ExactBlockPos> seenBlockEntities = new HashSet<>();
    }

    private record ExactBlockPos(int x, int y, int z) {}
}