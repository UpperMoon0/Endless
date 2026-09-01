package com.nstut.endless.vertical;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveHighYServerTest;
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
    private static final int FLUSH_INTERVAL_TICKS = 100;

    private static final Map<UUID, PlayerWindow> PLAYER_WINDOWS = new HashMap<>();
    private static PageSender sender;
    private static int ticks;

    private VerticalNetworkBridge() {}

    public static synchronized void registerSender(PageSender pageSender) {
        sender = pageSender;
    }

    public static void sendVisiblePagesForChunk(ServerPlayer player, LevelChunk chunk) {
        if (!EndlessLogicalHeights.isActive() || sender == null) {
            return;
        }
        int centerPageY = VerticalPageLayout.pageYForBlockY(player.getBlockY());
        for (int pageY = centerPageY - PAGE_RADIUS; pageY <= centerPageY + PAGE_RADIUS; pageY++) {
            sendPage(player, chunk, pageY);
        }
    }

    public static void tickServer(MinecraftServer server) {
        if (!EndlessLogicalHeights.isActive()) {
            return;
        }

        LiveHighYServerTest.tick(server);

        if (++ticks >= FLUSH_INTERVAL_TICKS) {
            ticks = 0;
            EndlessVerticalEngine.flushAll();
        }

        int viewDistance = server.getPlayerList().getViewDistance();
        PLAYER_WINDOWS.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int pageY = VerticalPageLayout.pageYForBlockY(player.getBlockY());
            String dimension = player.level().dimension().location().toString();
            PlayerWindow previous = PLAYER_WINDOWS.put(player.getUUID(), new PlayerWindow(dimension, pageY));
            if (previous != null && previous.pageY == pageY && previous.dimension.equals(dimension)) {
                continue;
            }

            ServerLevel level = player.serverLevel();
            ChunkPos center = player.chunkPosition();
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(center.x + dx, center.z + dz);
                    if (chunk != null) {
                        sendVisiblePagesForChunk(player, chunk);
                    }
                }
            }
        }
    }

    public static synchronized void shutdown() {
        EndlessVerticalEngine.closeAll();
        PLAYER_WINDOWS.clear();
        sender = null;
        ticks = 0;
    }

    private static void sendPage(ServerPlayer player, LevelChunk chunk, int pageY) {
        VerticalPagePos pos = new VerticalPagePos(chunk.getPos().x, pageY, chunk.getPos().z);
        MinecraftVerticalWorld world = EndlessVerticalEngine.world(player.level());
        if (!world.pageExists(pos)) {
            return;
        }
        VerticalPageSnapshot snapshot = world.snapshot(pos, true);
        if (snapshot == null) {
            return;
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
    }

    @FunctionalInterface
    public interface PageSender {
        void send(ServerPlayer player, VerticalPageSnapshot snapshot);
    }

    private record PlayerWindow(String dimension, int pageY) {}
}
