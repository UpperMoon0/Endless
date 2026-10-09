package com.nstut.endless.vertical;

import com.nstut.endless.debug.EndlessDebugTrace;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveHighYServerTest;
import com.nstut.endless.testing.LiveFarEnvelopeServerTest;
import com.nstut.endless.testing.LiveColdRestartServerTest;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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

    /** Retain chunk notifications until every page can enter the bounded send queue. */
    public static void sendVisiblePagesForChunk(ServerPlayer player, LevelChunk chunk) {
        if (!EndlessLogicalHeights.isActive() || sender == null) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        int viewDistance = server.getPlayerList().getViewDistance();
        WindowScan window = PLAYER_WINDOWS.get(player.getUUID());
        if (window == null || !window.matches(player, viewDistance)) {
            startWindow(player, viewDistance);
            window = PLAYER_WINDOWS.get(player.getUUID());
        }
        ChunkPos center = player.chunkPosition();
        // Keep deferred notifications bounded by the current tracking window.
        window.scan.discardNotificationsOutside(center.x(), center.z(), viewDistance + 1);
        if (Math.abs((long) chunk.getPos().x() - center.x()) <= viewDistance + 1
            && Math.abs((long) chunk.getPos().z() - center.z()) <= viewDistance + 1) {
            window.scan.notifyChunk(chunk.getPos().x(), chunk.getPos().z());
        }
    }

    public static void tickServer(MinecraftServer server) {
        if (!EndlessLogicalHeights.isActive()) {
            return;
        }

        verifyDenseCoreInvariantForLiveTest(server);
        LiveHighYServerTest.tick(server);
        LiveFarEnvelopeServerTest.tick(server);
        LiveColdRestartServerTest.tick(server);

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
            if (player == null || !player.level().dimension().identifier().toString().equals(pending.dimension())) return true;
            ChunkPos center = player.chunkPosition();
            VerticalPagePos pos = pending.pos();
            return Math.abs((long) pos.chunkX() - center.x()) > viewDistance + 1
                || Math.abs((long) pos.chunkZ() - center.z()) > viewDistance + 1
                || Math.abs((long) pos.pageY() - VerticalPageLayout.pageYForBlockY(player.getBlockY())) > PAGE_RADIUS;
        });
        PENDING_PAGES.drainBudgeted(64, 8, SEND_BYTES_PER_TICK, SEND_NANOS_PER_TICK, pending -> {
            ServerPlayer player = server.getPlayerList().getPlayer(pending.player());
            if (player == null) return 0;
            VerticalPagePos pos = pending.pos();
            LevelChunk chunk = player.level().getChunkSource().getChunkNow(pos.chunkX(), pos.chunkZ());
            return chunk == null ? 0 : sendPage(player, chunk, pos.pageY());
        });
    }

    private static boolean enqueue(UUID player, String dimension, VerticalPagePos pos) {
        int minPage = Math.floorDiv(EndlessHeights.getMinBuildHeight(), 512);
        int maxPage = Math.floorDiv(EndlessHeights.getMaxBuildHeight() - 1, 512);
        if (pos.pageY() < minPage || pos.pageY() > maxPage) return true;
        PendingPage request = new PendingPage(player, dimension, pos);
        return PENDING_PAGES.offerBounded(player, request, request, MAX_PENDING_PAGES_PER_PLAYER);
    }

    private static void startWindow(ServerPlayer player, int viewDistance) {
        UUID uuid = player.getUUID();
        PENDING_PAGES.removeOwner(uuid);
        PLAYER_WINDOWS.put(uuid, new WindowScan(player, viewDistance));
    }

    /** One resumable pass of loaded chunks, with no page disk probes. */
    private static void advanceScan(ServerPlayer player, WindowScan window) {
        ChunkPos center = player.chunkPosition();
        window.scan.discardNotificationsOutside(center.x(), center.z(), window.viewDistance + 1);
        window.scan.advance(SCAN_CHUNKS_PER_PLAYER_TICK,
            (x, z) -> Math.abs((long) x - center.x()) <= window.viewDistance + 1
                && Math.abs((long) z - center.z()) <= window.viewDistance + 1
                && player.level().getChunkSource().getChunkNow(x, z) != null,
            (x, page, z) -> enqueue(player.getUUID(), window.dimension, new VerticalPagePos(x, page, z)));
    }

    private static final class WindowScan {
        final String dimension;
        final int pageY, centerX, centerZ, viewDistance;
        final PageWindowScan scan;

        WindowScan(ServerPlayer player, int radius) {
            this.dimension = player.level().dimension().identifier().toString();
            this.pageY = VerticalPageLayout.pageYForBlockY(player.getBlockY());
            ChunkPos center = player.chunkPosition();
            this.centerX = center.x();
            this.centerZ = center.z();
            this.viewDistance = radius;
            this.scan = new PageWindowScan(centerX, centerZ, radius, pageY, PAGE_RADIUS);
        }

        boolean matches(ServerPlayer player, int radius) {
            ChunkPos current = player.chunkPosition();
            return dimension.equals(player.level().dimension().identifier().toString())
                && pageY == VerticalPageLayout.pageYForBlockY(player.getBlockY())
                && viewDistance == radius
                && Math.abs((long) current.x() - centerX) <= 3
                && Math.abs((long) current.z() - centerZ) <= 3;
        }

    }

    public static synchronized void shutdown() {
        EndlessVerticalEngine.closeAll();
        PLAYER_WINDOWS.clear();
        PENDING_PAGES.clear();
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
        int expectedMaxSection = Math.floorDiv(denseMax - 1, 16);

        if (level.getMinY() != denseMin
            || level.getHeight() != expectedHeight
            || level.getMinSectionY() != expectedMinSection
            || level.getMaxSectionY() != expectedMaxSection) {
            throw new IllegalStateException(
                "Logical range shifted vanilla dense section geometry: logical=["
                    + EndlessHeights.getMinBuildHeight() + "," + EndlessHeights.getMaxBuildHeight()
                    + ") dense=[" + denseMin + "," + denseMax + ") accessorMin="
                    + level.getMinY() + " accessorHeight=" + level.getHeight()
                    + " sections=[" + level.getMinSectionY() + "," + level.getMaxSectionY() + ")");
        }
        denseInvariantChecked = true;
    }

    private static long sendPage(ServerPlayer player, LevelChunk chunk, int pageY) {
        VerticalPagePos pos = new VerticalPagePos(chunk.getPos().x(), pageY, chunk.getPos().z());
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
            if (packet == null) {
                // Vanilla chunk BE data is applied before the sparse high-Y page on the
                // client, so blocks such as chests cannot be instantiated from that
                // packet: the dense LevelChunk still reports AIR at this position.
                // Re-send a generic BE packet after the sparse page. The client-side
                // sparse hook can now create/register the BE against the authoritative
                // high-Y state. Preserve custom update packets when a BE supplies one.
                packet = ClientboundBlockEntityDataPacket.create(blockEntity);
                EndlessDebugTrace.log("BE_SYNC_FALLBACK", "player=" + player.getGameProfile().name()
                    + " pos=" + blockEntity.getBlockPos() + " type=" + blockEntity.getType());
            } else {
                EndlessDebugTrace.log("BE_SYNC_CUSTOM", "player=" + player.getGameProfile().name()
                    + " pos=" + blockEntity.getBlockPos() + " packet=" + packet.getClass().getName());
            }
            player.connection.send(packet);
        }
        return snapshot.payloadBytes() + 64L;
    }

    @FunctionalInterface
    public interface PageSender {
        void send(ServerPlayer player, VerticalPageSnapshot snapshot);
    }

}
