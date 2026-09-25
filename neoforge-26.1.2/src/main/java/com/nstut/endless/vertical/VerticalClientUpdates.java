package com.nstut.endless.vertical;

import com.nstut.endless.debug.EndlessDebugTrace;
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
        MinecraftVerticalWorld world = EndlessVerticalEngine.world(client.level);
        world.applySnapshot(snapshot);
        VerticalPagePos pos = snapshot.pos();
        EndlessDebugTrace.log("PAGE_APPLY", "page=" + pos + " sections=" + snapshot.sections().size());
        BlockEntityPageRefreshScanner.schedule(pos);
        int x = pos.chunkX() << 4;
        int z = pos.chunkZ() << 4;
        // Neighbor faces and boundary light can change too. The work is bounded
        // to 3 x 34 x 3 sections regardless of the configured world height.
        client.levelRenderer.setBlocksDirty(x - 1, VerticalPageLayout.pageMinBlockY(pos.pageY()) - 1, z - 1,
            x + 16, VerticalPageLayout.pageMaxBlockY(pos.pageY()) + 1, z + 16);

        // Chest-family and other vanilla block entities are not guaranteed to
        // send a standalone BE-data packet. The sparse page itself is therefore
        // the authoritative generic signal that a BE-owning section may need a
        // delayed rebuild after the moving render grid reaches this high Y.
        for (VerticalPageSnapshot.SectionData section : snapshot.sections()) {
            int sectionY = VerticalPageLayout.sectionY(pos.pageY(), section.localSectionY());
            if (world.sectionMayContainBlockEntity(pos.chunkX(), sectionY, pos.chunkZ())) {
                queueSectionRenderRefresh(new BlockPos(x, sectionY << 4, z));
            }
        }
        tick(client);
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
        queueSectionRenderRefresh(pos, true);
        tick(client);
    }

    private static void queueSectionRenderRefresh(BlockPos pos) {
        queueSectionRenderRefresh(pos, true);
    }

    private static void queueSectionRenderRefreshIfAbsent(BlockPos pos) {
        queueSectionRenderRefresh(pos, false);
    }

    private static void queueSectionRenderRefresh(BlockPos pos, boolean resetExisting) {
        SectionKey key = new SectionKey(
            Math.floorDiv(pos.getX(), 16),
            Math.floorDiv(pos.getY(), 16),
            Math.floorDiv(pos.getZ(), 16));
        PendingRefresh previous;
        if (resetExisting) {
            previous = PENDING_BLOCK_ENTITY_SECTIONS.put(key, new PendingRefresh());
        } else {
            previous = PENDING_BLOCK_ENTITY_SECTIONS.putIfAbsent(key, new PendingRefresh());
        }
        if (resetExisting || previous == null) {
            LiveRenderProbe.recordBlockEntityRefreshQueued(pos);
            EndlessDebugTrace.log("BE_REFRESH_QUEUE", "section=" + key
                + " resetExisting=" + resetExisting + " replaced=" + (previous != null));
        }
    }

    /** Retry queued sparse BE invalidations once the target section is in the render window. */
    public static void tick(Minecraft client) {
        if (client.level == null) {
            PENDING_BLOCK_ENTITY_SECTIONS.clear();
            EndlessDebugTrace.state("refresh-level", "BE_REFRESH_LEVEL", "level=null pending=0");
            return;
        }
        BlockEntityPageRefreshScanner.tick(
            client, VerticalClientUpdates::queueSectionRenderRefreshIfAbsent);
        if (PENDING_BLOCK_ENTITY_SECTIONS.isEmpty()) return;

        ViewArea viewArea = ((LevelRendererAccessor)(Object)client.levelRenderer).endless$getViewArea();
        if (viewArea == null) {
            EndlessDebugTrace.state("refresh-viewarea", "BE_REFRESH_VIEWAREA",
                "viewArea=null pending=" + PENDING_BLOCK_ENTITY_SECTIONS.size());
            return;
        }
        Iterator<Map.Entry<SectionKey, PendingRefresh>> iterator = PENDING_BLOCK_ENTITY_SECTIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SectionKey, PendingRefresh> entry = iterator.next();
            SectionKey key = entry.getKey();
            PendingRefresh pending = entry.getValue();
            BlockPos expectedOrigin = new BlockPos(key.x() << 4, key.y() << 4, key.z() << 4);
            SectionRenderDispatcher.RenderSection renderSection =
                ((ViewAreaAccessor)(Object)viewArea).endless$invokeGetRenderSectionAt(expectedOrigin);
            if (renderSection == null) {
                EndlessDebugTrace.state("refresh-section:" + key, "BE_REFRESH_SECTION",
                    "section=" + key + " slot=null");
                pending.resetStability();
                continue;
            }

            // The moving render grid is modulo-indexed. Merely being inside the
            // current Y window is insufficient while repositionCamera() is
            // rebasing: the slot can still represent its previous section.
            BlockPos actualOrigin = renderSection.getRenderOrigin();
            if (Math.floorDiv(actualOrigin.getX(), 16) != key.x()
                || Math.floorDiv(actualOrigin.getY(), 16) != key.y()
                || Math.floorDiv(actualOrigin.getZ(), 16) != key.z()) {
                EndlessDebugTrace.state("refresh-section:" + key, "BE_REFRESH_SECTION",
                    "section=" + key + " slotOrigin=" + actualOrigin + " match=false");
                pending.resetStability();
                continue;
            }

            EndlessDebugTrace.state("refresh-section:" + key, "BE_REFRESH_SECTION",
                "section=" + key + " slotOrigin=" + actualOrigin + " match=true");
            pending.stableTicks++;
            if (pending.stableTicks < REQUIRED_STABLE_TICKS) continue;
            if (pending.cooldownTicks > 0) {
                pending.cooldownTicks--;
                continue;
            }

            client.levelRenderer.setSectionDirtyWithNeighbors(key.x(), key.y(), key.z());
            LiveRenderProbe.recordBlockEntityRefreshDirtied(expectedOrigin);
            EndlessDebugTrace.log("BE_REFRESH_DIRTY", "section=" + key
                + " invalidation=" + (pending.invalidations + 1)
                + " stableTicks=" + pending.stableTicks);

            pending.invalidations++;
            pending.cooldownTicks = RETRY_INTERVAL_TICKS;
            boolean compiledBlockEntity = renderSection.getSectionMesh().getRenderableBlockEntities().stream()
                .anyMatch(blockEntity ->
                    Math.floorDiv(blockEntity.getBlockPos().getX(), 16) == key.x()
                        && Math.floorDiv(blockEntity.getBlockPos().getY(), 16) == key.y()
                        && Math.floorDiv(blockEntity.getBlockPos().getZ(), 16) == key.z());
            EndlessDebugTrace.state("refresh-compiled:" + key, "BE_REFRESH_COMPILED",
                "section=" + key + " compiledBE=" + compiledBlockEntity
                    + " meshBECount=" + renderSection.getSectionMesh().getRenderableBlockEntities().size()
                    + " invalidations=" + pending.invalidations);
            if (compiledBlockEntity || pending.invalidations >= MAX_INVALIDATIONS) {
                EndlessDebugTrace.log("BE_REFRESH_DONE", "section=" + key
                    + " reason=" + (compiledBlockEntity ? "compiled-be" : "max-invalidations"));
                iterator.remove();
            }
        }
    }

    public static void reset() {
        PENDING_BLOCK_ENTITY_SECTIONS.clear();
        BlockEntityPageRefreshScanner.reset();
        EndlessDebugTrace.reset();
        EndlessDebugTrace.log("CLIENT_RESET", "refreshPending=0");
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
