package com.nstut.endless.vertical;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveHighYServerTest;
import com.nstut.endless.testing.LiveFarEnvelopeServerTest;
import com.nstut.endless.testing.LiveColdRestartServerTest;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Loader-neutral server-side vertical page synchronization. */
public final class VerticalNetworkBridge {
    private static final int PAGE_RADIUS = 1;
    private static final int FLUSH_INTERVAL_TICKS = 10;

    private static final int SCAN_CHUNKS_PER_PLAYER_TICK = 16;
    private static final int MAX_PENDING_PAGES_PER_PLAYER = 256;
    private static final long SEND_BYTES_PER_TICK = 4L << 20;
    private static final long SEND_NANOS_PER_TICK = 4_000_000L;
    private static final Map<UUID, WindowScan> PLAYER_WINDOWS = new HashMap<>();
    private record PendingPage(UUID player,String dimension,VerticalPagePos pos) {}
    private static final FairWorkQueue<UUID,PendingPage,PendingPage> PENDING_PAGES=new FairWorkQueue<>();
    private static PageSender sender;
    private static int ticks;
    private static boolean denseInvariantChecked;

    private VerticalNetworkBridge() {}

    public static synchronized void registerSender(PageSender pageSender) {
        sender = pageSender;
    }

    /** Chunk-load notifications enqueue candidates; disk checks happen only in the bounded drain. */
    public static void sendVisiblePagesForChunk(ServerPlayer player, LevelChunk chunk) {
        if (!EndlessLogicalHeights.isActive() || sender == null) return;
        int centerPageY = VerticalPageLayout.pageYForBlockY(player.getBlockY());
        String dimension = player.level().dimension().location().toString();
        for (int pageY = centerPageY - PAGE_RADIUS; pageY <= centerPageY + PAGE_RADIUS; pageY++) {
            enqueue(player.getUUID(), dimension, new VerticalPagePos(chunk.getPos().x, pageY, chunk.getPos().z));
        }
    }

    /** Client page transition acknowledgement restarts a near-first bounded scan. */
    public static void refreshPlayerWindow(ServerPlayer player) {
        if (!EndlessLogicalHeights.isActive() || sender == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        startWindow(player, server.getPlayerList().getViewDistance());
    }

    public static void tickServer(MinecraftServer server) {
        if (!EndlessLogicalHeights.isActive()) {
            return;
        }

        verifyDenseCoreInvariantForLiveTest(server);
        LiveHighYServerTest.tick(server);
        LiveFarEnvelopeServerTest.tick(server);
        LiveColdRestartServerTest.tick(server);
        com.nstut.endless.testing.LiveCreateGameTests.tick(server);
        com.nstut.endless.testing.LiveCreatePlayerWorkflowServerTest.tick(server);
        com.nstut.endless.testing.LiveCreateTrainServerTest.tick(server);
        com.nstut.endless.testing.LiveCreateSurvivalServerTest.tick(server);

        if (++ticks >= FLUSH_INTERVAL_TICKS) {
            ticks = 0;
            EndlessVerticalEngine.flushBudgeted(4, 2_000_000L, 4L << 20);
        }

        int viewDistance = server.getPlayerList().getViewDistance();
        PLAYER_WINDOWS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            WindowScan window = PLAYER_WINDOWS.get(player.getUUID());
            if (window == null || !window.matches(player, viewDistance)) {
                startWindow(player, viewDistance);
                window = PLAYER_WINDOWS.get(player.getUUID());
            }
            advanceScan(player, window);
        }
        PENDING_PAGES.removeOwners(uuid -> server.getPlayerList().getPlayer(uuid) == null);
        // Prune obsolete entries BEFORE draining; old teleports must not use a send slot.
        PENDING_PAGES.removeIf(pending -> {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player == null || !player.level().dimension().location().toString().equals(pending.dimension())) return true;
            ChunkPos center = player.chunkPosition();
            VerticalPagePos pos = pending.pos();
            return Math.abs((long) pos.chunkX() - center.x) > viewDistance + 1
                || Math.abs((long) pos.chunkZ() - center.z) > viewDistance + 1
                || Math.abs((long) pos.pageY() - VerticalPageLayout.pageYForBlockY(player.getBlockY())) > PAGE_RADIUS;
        });
        PENDING_PAGES.drainBudgeted(64, 8, SEND_BYTES_PER_TICK, SEND_NANOS_PER_TICK, pending -> {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player == null) return 0;
            VerticalPagePos pos = pending.pos();
            LevelChunk chunk = player.serverLevel().getChunkSource().getChunkNow(pos.chunkX(), pos.chunkZ());
            return chunk == null ? 0 : sendPage(player, chunk, pos.pageY());
        });
        // Player edits retain the existing immediate update path.
        SparseChunkUpdateQueue.flush();
    }

    private static void enqueue(UUID player, String dimension, VerticalPagePos pos) {
        int minPage = Math.floorDiv(EndlessHeights.getMinBuildHeight(), 512);
        int maxPage = Math.floorDiv(EndlessHeights.getMaxBuildHeight() - 1, 512);
        if (pos.pageY() < minPage || pos.pageY() > maxPage) return;
        PendingPage request = new PendingPage(player, dimension, pos);
        PENDING_PAGES.offerBounded(player, request, request, MAX_PENDING_PAGES_PER_PLAYER);
    }

    private static void startWindow(ServerPlayer player, int viewDistance) {
        UUID uuid = player.getUUID();
        PENDING_PAGES.removeOwner(uuid);
        PLAYER_WINDOWS.put(uuid, new WindowScan(player, viewDistance));
    }

    /** One pass of loaded chunks nearest to the player, with no page disk probes. */
    private static void advanceScan(ServerPlayer player, WindowScan window) {
        int total = (2 * window.viewDistance + 1) * (2 * window.viewDistance + 1);
        for (int n = 0; n < SCAN_CHUNKS_PER_PLAYER_TICK && window.cursor < total; n++) {
            if (PENDING_PAGES.ownerSize(player.getUUID()) >= MAX_PENDING_PAGES_PER_PLAYER) break;
            ChunkPos pos = window.nextChunk();
            LevelChunk chunk = player.serverLevel().getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) sendVisiblePagesForChunk(player, chunk);
        }
    }

    private static final class WindowScan {
        final String dimension;
        final int pageY, centerX, centerZ, viewDistance;
        int cursor;

        WindowScan(ServerPlayer player, int radius) {
            this.dimension = player.level().dimension().location().toString();
            this.pageY = VerticalPageLayout.pageYForBlockY(player.getBlockY());
            ChunkPos center = player.chunkPosition();
            this.centerX = center.x;
            this.centerZ = center.z;
            this.viewDistance = radius;
        }

        boolean matches(ServerPlayer player, int radius) {
            ChunkPos current = player.chunkPosition();
            return dimension.equals(player.level().dimension().location().toString())
                && pageY == VerticalPageLayout.pageYForBlockY(player.getBlockY())
                && viewDistance == radius
                && Math.abs((long) current.x - centerX) <= 3
                && Math.abs((long) current.z - centerZ) <= 3;
        }

        ChunkPos nextChunk() {
            int remaining = cursor++;
            if (remaining == 0) return new ChunkPos(centerX, centerZ);
            remaining--;
            for (int r = 1; r <= viewDistance; r++) {
                int edge = 2 * r, perimeter = 4 * edge;
                if (remaining >= perimeter) { remaining -= perimeter; continue; }
                int dx, dz;
                if (remaining < edge) { dx = -r + remaining; dz = -r; }
                else if (remaining < 2 * edge) { dx = r; dz = -r + remaining - edge; }
                else if (remaining < 3 * edge) { dx = r - (remaining - 2 * edge); dz = r; }
                else { dx = -r; dz = r - (remaining - 3 * edge); }
                return new ChunkPos(centerX + dx, centerZ + dz);
            }
            throw new IllegalStateException("Window scan cursor outside view");
        }
    }

    public static synchronized void shutdown() {
        EndlessVerticalEngine.closeAll();
        PLAYER_WINDOWS.clear();
        PENDING_PAGES.clear();
        SparseChunkUpdateQueue.clear();
        // The sender is loader-global process state, not server-instance state.
        // Clearing it here breaks the second integrated-server session in the
        // same Minecraft JVM: the mod bootstrap does not run again, so saved
        // sparse pages can never be resynchronized to the client after rejoin.
        ticks = 0;
        denseInvariantChecked = false;
    }

    private static void verifyDenseCoreInvariantForLiveTest(MinecraftServer server) {
        if (denseInvariantChecked
            || !Boolean.parseBoolean(System.getProperty(LiveHighYServerTest.SYSTEM_PROPERTY, "false"))) {
            return;
        }

        ServerLevel level = server.overworld();
        int denseMin = EndlessHeights.getDenseMinBuildHeight();
        int denseMax = EndlessHeights.getDenseMaxBuildHeight();
        int expectedHeight = denseMax - denseMin;
        int expectedMinSection = Math.floorDiv(denseMin, 16);
        int expectedMaxSection = Math.floorDiv(denseMax - 1, 16) + 1;

        if (level.getMinBuildHeight() != denseMin
            || level.getHeight() != expectedHeight
            || level.getMinSection() != expectedMinSection
            || level.getMaxSection() != expectedMaxSection) {
            throw new IllegalStateException(
                "Logical range shifted vanilla dense section geometry: logical=["
                    + EndlessHeights.getMinBuildHeight() + "," + EndlessHeights.getMaxBuildHeight()
                    + ") dense=[" + denseMin + "," + denseMax + ") accessorMin="
                    + level.getMinBuildHeight() + " accessorHeight=" + level.getHeight()
                    + " sections=[" + level.getMinSection() + "," + level.getMaxSection() + ")");
        }
        denseInvariantChecked = true;
    }

    private static long sendPage(ServerPlayer player, LevelChunk chunk, int pageY) {
        VerticalPagePos pos = new VerticalPagePos(chunk.getPos().x, pageY, chunk.getPos().z);
        MinecraftVerticalWorld world = EndlessVerticalEngine.world(player.level());
        if (!world.pageExists(pos)) {
            return 0;
        }
        VerticalPageSnapshot snapshot = world.snapshot(pos, true);
        if (snapshot == null) {
            return 0;
        }

        sender.send(player, snapshot);

        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (VerticalPageLayout.pageYForBlockY(blockEntity.getBlockPos().getY()) != pageY) {
                continue;
            }
            if (!EndlessVerticalEngine.isExtendedY(player.level(), blockEntity.getBlockPos().getY())) {
                continue;
            }
            Packet<ClientGamePacketListener> packet = blockEntity.getUpdatePacket();
            if (packet != null) {
                player.connection.send(packet);
            }
        }
        return snapshot.payloadBytes() + 64L;
    }

    @FunctionalInterface
    public interface PageSender {
        void send(ServerPlayer player, VerticalPageSnapshot snapshot);
    }

}
