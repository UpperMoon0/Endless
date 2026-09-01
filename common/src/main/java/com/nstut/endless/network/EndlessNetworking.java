package com.nstut.endless.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic transport for the height sync.
 *
 * <p>Forge uses the play-phase {@link Sender} registered by
 * {@code EndlessForge.setup}; the {@code PlayerListMixin} consults
 * {@link #shouldEnforceRange} for each joining player and either sends a
 * play-stage packet or disconnects vanilla clients that landed on an extended
 * range server.</p>
 *
 * <p>Fabric uses the login-phase {@link LoginSync} registered by
 * {@code EndlessFabric.onInitialize}; the Fabric client declares the channel
 * before the play stage, so the login-phase handshake can deliver the
 * authoritative range before any chunk/world packet, and vanilla clients are
 * disconnected by the login pipeline itself when the server has an extended
 * range. {@link #shouldEnforceRange} returns {@code false} on Fabric, so the
 * play-phase mixin is a no-op there.</p>
 */
public final class EndlessNetworking {

    private static volatile Sender sender;
    private static volatile LoginSync loginSync;
    private static volatile boolean loginSyncRegistered;

    private EndlessNetworking() {
    }

    public static void registerSender(Sender sender) {
        EndlessNetworking.sender = sender;
    }

    public static void registerLoginSync(LoginSync loginSync) {
        EndlessNetworking.loginSync = loginSync;
        EndlessNetworking.loginSyncRegistered = true;
    }

    /**
     * Whether the loader has installed a login-phase height sync. When this
     * returns {@code true} the play-phase {@code PlayerListMixin} must NOT
     * disconnect vanilla clients, because login already handled the rejection.
     */
    public static boolean isLoginPhaseSync() {
        return loginSyncRegistered;
    }

    /**
     * Whether the joining player still needs the play-stage range enforcement.
     * True on Forge (where the play-stage sync is the only mechanism), false
     * on Fabric (where the login-phase handshake has already happened or the
     * vanilla client has been rejected).
     */
    public static boolean shouldEnforceRange(ServerPlayer player) {
        if (isLoginPhaseSync()) {
            return false;
        }
        Sender current = sender;
        return current == null || !current.canSend(player);
    }

    /**
     * Whether the joining player's client can receive the play-stage height
     * sync (i.e. has Endless installed and listening on the sync channel).
     * Forge-only; Fabric's login-phase sync does not use this.
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

    /**
     * Loader-agnostic hook for the login-phase height handshake. The actual
     * registration of the login channel and handler is loader-specific; this
     * interface just lets the rest of the mod know the loader has done so.
     */
    public interface LoginSync {
    }
}
