package com.nstut.endless.vertical;

import com.nstut.endless.debug.EndlessDebugTrace;
import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
        EndlessDebugTrace.log("BE_SCAN_SCHEDULE", "page=" + pos
            + " minY=" + VerticalPageLayout.pageMinBlockY(pos.pageY())
            + " maxY=" + VerticalPageLayout.pageMaxBlockY(pos.pageY()));
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
                EndlessDebugTrace.state("scan-chunk:" + pagePos, "BE_SCAN_CHUNK",
                    "page=" + pagePos + " chunk=present beCount=" + chunk.getBlockEntities().size());
                int minY = VerticalPageLayout.pageMinBlockY(pagePos.pageY());
                int maxY = VerticalPageLayout.pageMaxBlockY(pagePos.pageY());
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getY() < minY || pos.getY() > maxY) {
                        continue;
                    }
                    ExactBlockPos exact = new ExactBlockPos(pos.getX(), pos.getY(), pos.getZ());
                    if (pending.seenBlockEntities.add(exact)) {
                        BlockState cachedStateBefore = blockEntity.getBlockState();
                        BlockState authoritativeState = client.level.getBlockState(pos);
                        boolean validAuthoritative = blockEntity.getType().isValid(authoritativeState);
                        boolean repairNeeded = validAuthoritative
                            && (cachedStateBefore != authoritativeState
                                || !blockEntity.hasLevel()
                                || blockEntity.isRemoved());
                        EndlessDebugTrace.log("BE_DISCOVER", "pos=" + pos
                            + " type=" + blockEntity.getType()
                            + " worldState=" + authoritativeState
                            + " cachedState=" + cachedStateBefore
                            + " validWorld=" + validAuthoritative
                            + " hasLevel=" + blockEntity.hasLevel()
                            + " removed=" + blockEntity.isRemoved()
                            + " repairNeeded=" + repairNeeded);
                        // Initial chunk BE data can be materialized before the sparse page state is
                        // installed. In that ordering the LevelChunk map contains the correct BE
                        // instance, but the BE caches AIR as its BlockState. 26.1's final
                        // BlockEntityRenderDispatcher rejects such an instance even when the
                        // section compiler has already captured it. Re-register against the
                        // authoritative sparse state so the cached state, level/removal flags and
                        // client ticker are all repaired before the render-section rebuild.
                        if (repairNeeded) {
                            chunk.addAndRegisterBlockEntity(blockEntity);
                            EndlessDebugTrace.log("BE_REPAIR", "pos=" + pos
                                + " worldState=" + client.level.getBlockState(pos)
                                + " cachedAfter=" + blockEntity.getBlockState()
                                + " hasLevelAfter=" + blockEntity.hasLevel()
                                + " removedAfter=" + blockEntity.isRemoved()
                                + " validCachedAfter=" + blockEntity.getType().isValid(blockEntity.getBlockState()));
                        }
                        LiveRenderProbe.recordBlockEntityScannerDiscovered(pos);
                        onDiscoveredBlockEntity.accept(pos);
                    }
                }
            } else {
                EndlessDebugTrace.state("scan-chunk:" + pagePos, "BE_SCAN_CHUNK",
                    "page=" + pagePos + " chunk=missing");
            }

            pending.scans++;
            if (pending.scans >= MAX_SCANS) {
                EndlessDebugTrace.log("BE_SCAN_EXPIRE", "page=" + pagePos
                    + " scans=" + pending.scans + " seen=" + pending.seenBlockEntities.size());
                iterator.remove();
            }
        }
    }

    public static void reset() {
        PENDING.clear();
        EndlessDebugTrace.log("BE_SCAN_RESET", "pending=0");
    }

    private static final class PendingPageScan {
        private int cooldownTicks;
        private int scans;
        private final Set<ExactBlockPos> seenBlockEntities = new HashSet<>();
    }

    private record ExactBlockPos(int x, int y, int z) {}
}