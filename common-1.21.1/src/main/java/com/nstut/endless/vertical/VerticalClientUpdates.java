package com.nstut.endless.vertical;

import com.nstut.endless.mixin.accessor.LevelRendererAccessor;
import com.nstut.endless.mixin.accessor.ViewAreaAccessor;
import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Client-thread invalidation for received sparse pages and their block entities. */
public final class VerticalClientUpdates {
    private static final int REQUIRED_STABLE_TICKS = 2;
    private static final int RETRY_INTERVAL_TICKS = 5;
    private static final int MAX_INVALIDATIONS = 8;
    private static final Map<SectionKey, PendingRefresh> PENDING_BLOCK_ENTITY_SECTIONS = new HashMap<>();

    private VerticalClientUpdates() {}

    public static void apply(Minecraft client, VerticalPageSnapshot snapshot) {
        EndlessVerticalEngine.world(client.level).applySnapshot(snapshot);
        VerticalPagePos pos = snapshot.pos();
        int x = pos.chunkX() << 4;
        int z = pos.chunkZ() << 4;
        // Neighbor faces and boundary light can change too. The work is bounded
        // to 3 x 34 x 3 sections regardless of the configured world height.
        client.levelRenderer.setBlocksDirty(x - 1, VerticalPageLayout.pageMinBlockY(pos.pageY()) - 1, z - 1,
            x + 16, VerticalPageLayout.pageMaxBlockY(pos.pageY()) + 1, z + 16);
    }

    /**
     * Queue a sparse block-entity render rebuild. BE packets can arrive while a
     * new ViewArea exists but before its moving Y window has rebased to the
     * player's restored high-Y position, or while a rebuild from the page packet
     * is already in flight. Keep the section pending through a short stable
     * window and retry a bounded number of invalidations so neither race can
     * leave a permanently stale compiled section.
     */
    public static void queueBlockEntityRenderRefresh(Minecraft client, BlockPos pos) {
        SectionKey key = new SectionKey(
            Math.floorDiv(pos.getX(), 16),
            Math.floorDiv(pos.getY(), 16),
            Math.floorDiv(pos.getZ(), 16));
        // Multiple BE packets for one section arrive back-to-back. Replacing the
        // pending entry lets the last packet define the settle/retry window.
        PENDING_BLOCK_ENTITY_SECTIONS.put(key, new PendingRefresh());
        LiveRenderProbe.recordBlockEntityRefreshQueued(pos);
        tick(client);
    }

    /** Retry queued sparse BE invalidations once the target section is in the render window. */
    public static void tick(Minecraft client) {
        if (client.level == null) {
            PENDING_BLOCK_ENTITY_SECTIONS.clear();
            return;
        }
        if (PENDING_BLOCK_ENTITY_SECTIONS.isEmpty()) return;

        ViewArea viewArea = ((LevelRendererAccessor)(Object)client.levelRenderer).endless$getViewArea();
        if (viewArea == null) return;
        Iterator<Map.Entry<SectionKey, PendingRefresh>> iterator = PENDING_BLOCK_ENTITY_SECTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SectionKey, PendingRefresh> entry = iterator.next();
            SectionKey key = entry.getKey();
            PendingRefresh pending = entry.getValue();
            BlockPos expectedOrigin = new BlockPos(key.x() << 4, key.y() << 4, key.z() << 4);
            SectionRenderDispatcher.RenderSection renderSection =
                ((ViewAreaAccessor)(Object)viewArea).endless$invokeGetRenderSectionAt(expectedOrigin);
            if (renderSection == null) {
                pending.resetStability();
                continue;
            }

            // The moving render grid is modulo-indexed. Merely being inside the
            // current Y window is insufficient while repositionCamera() is
            // rebasing: the slot can still represent its previous section.
            BlockPos actualOrigin = renderSection.getOrigin();
            if (Math.floorDiv(actualOrigin.getX(), 16) != key.x()
                || Math.floorDiv(actualOrigin.getY(), 16) != key.y()
                || Math.floorDiv(actualOrigin.getZ(), 16) != key.z()) {
                pending.resetStability();
                continue;
            }

            pending.stableTicks++;
            if (pending.stableTicks < REQUIRED_STABLE_TICKS) continue;
            if (pending.cooldownTicks > 0) {
                pending.cooldownTicks--;
                continue;
            }

            client.levelRenderer.setSectionDirtyWithNeighbors(key.x(), key.y(), key.z());
            LiveRenderProbe.recordBlockEntityRefreshDirtied(expectedOrigin);
            pending.invalidations++;
            pending.cooldownTicks = RETRY_INTERVAL_TICKS;
            if (pending.invalidations >= MAX_INVALIDATIONS) {
                iterator.remove();
            }
        }
    }

    public static void reset() {
        PENDING_BLOCK_ENTITY_SECTIONS.clear();
    }

    private static final class PendingRefresh {
        private int stableTicks;
        private int cooldownTicks;
        private int invalidations;

        private void resetStability() {
            stableTicks = 0;
            cooldownTicks = 0;
        }
    }

    private record SectionKey(int x, int y, int z) {}
}
