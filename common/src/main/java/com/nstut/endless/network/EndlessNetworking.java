package com.nstut.endless.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic transport for the height sync packet. Fabric and Forge
 * register their transport-specific implementations at startup.
 */
public final class EndlessNetworking {

    private static volatile Sender sender;

    private EndlessNetworking() {
    }

    public static void registerSender(Sender sender) {
        EndlessNetworking.sender = sender;
    }

    /**
     * Whether the joining player's client can receive the height sync (i.e.
     * has Endless installed and listening on the sync channel).
     */
    public static boolean canSend(ServerPlayer player) {
        Sender current = sender;
        return current != null && current.canSend(player);
    }

    public static void sendHeights(ServerPlayer player, int minBuildHeight, int maxBuildHeight) {
        Sender current = sender;
        if (current != null) {
            current.send(player, minBuildHeight, maxBuildHeight);
        }
    }

    public interface Sender {
        boolean canSend(ServerPlayer player);

        void send(ServerPlayer player, int minBuildHeight, int maxBuildHeight);
    }
}
