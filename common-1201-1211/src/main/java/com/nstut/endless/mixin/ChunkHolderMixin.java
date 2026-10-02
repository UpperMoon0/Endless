package com.nstut.endless.mixin;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalPageLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Keeps sparse block changes out of ChunkHolder's dense per-section update array.
 *
 * <p>Vanilla sizes {@code changedBlocksPerSection} from the bounded dense core.
 * A logical Y such as 1,000,000 therefore produces a section index far beyond
 * that array. Sparse positions are already represented by Endless storage, so
 * they are synchronized at vanilla's broadcast point with normal block/block-entity packets using
 * the negotiated extended BlockPos codec instead of entering vanilla's dense
 * section batching path.</p>
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    private static final int ENDLESS_PAGE_RADIUS = 1;

    @Shadow @Final private ChunkHolder.PlayerProvider playerProvider;
    @Unique private final Map<BlockPos, Set<ServerPlayer>> endless$sparseChanges = new LinkedHashMap<>();

    @Shadow
    public abstract LevelChunk getTickingChunk();

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void endless$blockChanged(BlockPos pos, CallbackInfo ci) {
        LevelChunk chunk = this.getTickingChunk();
        if (chunk == null) {
            return;
        }

        Level level = chunk.getLevel();
        if (!EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            return;
        }

        // Never let a sparse section index reach vanilla's dense ShortSet[].
        ci.cancel();
        // Match vanilla's deferred serialization. Create's BeltInventory.write
        // drains pending insertions/removals, so calling getUpdatePacket here
        // can mutate the inventory while its native tick iterator is active.
        int page = VerticalPageLayout.pageYForBlockY(pos.getY());
        Set<ServerPlayer> recipients = endless$sparseChanges.computeIfAbsent(pos.immutable(), ignored -> new LinkedHashSet<>());
        for (ServerPlayer player : this.playerProvider.getPlayers(chunk.getPos(), false))
            if (Math.abs(VerticalPageLayout.pageYForBlockY(player.getBlockY()) - page) <= ENDLESS_PAGE_RADIUS) recipients.add(player);
        if (Boolean.getBoolean("endless.liveCreatePlayerWorkflows") && pos.getX() == 32 && pos.getZ() == 34)
            System.out.println("ENDLESS_SPARSE_CHANGE_TRACE pos="+pos+" state="+level.getBlockState(pos)+" recipients="+recipients.size()+" tick="+level.getGameTime());
    }

    @Inject(method = "broadcastChanges", at = @At("TAIL"))
    private void endless$broadcastSparseChanges(LevelChunk chunk, CallbackInfo ci) {
        if (endless$sparseChanges.isEmpty()) return;
        Map<BlockPos, Set<ServerPlayer>> changes = new LinkedHashMap<>(endless$sparseChanges);
        endless$sparseChanges.clear();
        Level level = chunk.getLevel();
        Map<Integer, Set<ServerPlayer>> changedPages = new LinkedHashMap<>();
        for (var change : changes.entrySet()) {
            // Include players who began watching this page between the change and
            // vanilla's broadcast, while retaining the original audience.
            int page = VerticalPageLayout.pageYForBlockY(change.getKey().getY());
            for (ServerPlayer player : this.playerProvider.getPlayers(chunk.getPos(), false))
                if (Math.abs(VerticalPageLayout.pageYForBlockY(player.getBlockY()) - page) <= ENDLESS_PAGE_RADIUS) change.getValue().add(player);
            if (change.getValue().isEmpty()) continue;
            BlockPos pos = change.getKey();
            BlockState state = level.getBlockState(pos);
            if (Boolean.getBoolean("endless.liveCreatePlayerWorkflows") && pos.getX() == 32 && pos.getZ() == 34)
                System.out.println("ENDLESS_SPARSE_BROADCAST_TRACE pos="+pos+" state="+state+" recipients="+change.getValue().size()+" tick="+level.getGameTime());
            ClientboundBlockUpdatePacket blockPacket = new ClientboundBlockUpdatePacket(pos, state);
            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            Packet<ClientGamePacketListener> blockEntityPacket =
                blockEntity == null ? null : blockEntity.getUpdatePacket();

            // Preserve the event's audience when a player changes pages before
            // broadcast. Still reject delivery into another world.
            for (ServerPlayer player : change.getValue()) {
                if (player.level() != level) continue;
                changedPages.computeIfAbsent(page, ignored -> new LinkedHashSet<>()).add(player);
                player.connection.send(blockPacket);
                if (blockEntityPacket != null) {
                    player.connection.send(blockEntityPacket);
                }
            }
        }
        // NeoForge can apply a queued full-page payload after native block
        // updates. Send one current revision per changed page/audience after
        // the native packets: older in-flight snapshots then cannot restore
        // stale state, and native BE callbacks still receive their packets.
        for (var changedPage : changedPages.entrySet())
            for (ServerPlayer player : changedPage.getValue())
                com.nstut.endless.vertical.VerticalNetworkBridge.sendPage(player, chunk, changedPage.getKey());
    }
}
