package com.nstut.endless.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic sender for the height sync packet. Fabric and Forge register
 * their transport-specific implementations at startup.
 */
public final class EndlessNetworking {

    private static volatile Sender sender;

    private EndlessNetworking() {
    }

    public static void registerSender(Sender sender) {
        EndlessNetworking.sender = sender;
    }

    public static void sendHeights(ServerPlayer player, int minBuildHeight, int maxBuildHeight) {
        Sender current = sender;
        if (current != null) {
            current.send(player, minBuildHeight, maxBuildHeight);
        }
    }

    @FunctionalInterface
    public interface Sender {
        void send(ServerPlayer player, int minBuildHeight, int maxBuildHeight);
    }
}
