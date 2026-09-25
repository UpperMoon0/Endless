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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Keeps sparse block changes out of ChunkHolder's dense per-section update array.
 *
 * <p>Vanilla sizes {@code changedBlocksPerSection} from the bounded dense core.
 * A logical Y such as 1,000,000 therefore produces a section index far beyond
 * that array. Sparse positions are already represented by Endless storage, so
 * they are synchronized directly with normal block/block-entity packets using
 * the negotiated extended BlockPos codec instead of entering vanilla's dense
 * section batching path.</p>
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {
    private static final int ENDLESS_PAGE_RADIUS = 1;

    @Shadow @Final private ChunkHolder.PlayerProvider playerProvider;

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

        List<ServerPlayer> players = this.playerProvider.getPlayers(chunk.getPos(), false);
        if (players.isEmpty()) {
            return;
        }

        int changedPageY = VerticalPageLayout.pageYForBlockY(pos.getY());
        BlockState state = level.getBlockState(pos);
        ClientboundBlockUpdatePacket blockPacket = new ClientboundBlockUpdatePacket(pos, state);
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        Packet<ClientGamePacketListener> blockEntityPacket =
            blockEntity == null ? null : blockEntity.getUpdatePacket();

        for (ServerPlayer player : players) {
            int playerPageY = VerticalPageLayout.pageYForBlockY(player.getBlockY());
            if (Math.abs(playerPageY - changedPageY) > ENDLESS_PAGE_RADIUS) {
                continue;
            }
            player.connection.send(blockPacket);
            if (blockEntityPacket != null) {
                player.connection.send(blockEntityPacket);
            }
        }
    }
}
