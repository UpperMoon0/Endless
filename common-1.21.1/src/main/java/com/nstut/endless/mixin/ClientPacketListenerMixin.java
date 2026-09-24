package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.testing.LiveJoinTest;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.VerticalClientUpdates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client packet hooks for authoritative height setup and sparse block entities.
 *
 * <p>Registered in the mixin config's client section, so it never loads on
 * dedicated servers.</p>
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void endless$assertLoginRange(ClientboundLoginPacket packet, CallbackInfo ci) {
        EndlessHeights.applyVanillaBaselineIfUnapplied();
        LiveJoinTest.assertPreLoginRange();
    }

    /**
     * Sparse page snapshots install section state outside LevelChunk's dense
     * section array. When a player enters such a page, the following vanilla
     * block-entity data packet therefore has no pre-created client block entity
     * to update. Create and fully register it first, then apply the vanilla
     * update tag.
     */
    @Inject(method = "handleBlockEntityData", at = @At("HEAD"), cancellable = true)
    private void endless$handleSparseBlockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        BlockPos pos = packet.getPos();
        if (level == null || !EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            return;
        }

        // NeoForge can dispatch the vanilla BE packet while the preceding sparse
        // page payload is still queued for client-thread application. Queue the
        // durable section refresh before consulting the current block state so
        // that this ordering race cannot skip the rebuild entirely. The bounded
        // retry loop waits until the render window is stable and harmlessly
        // expires if no valid BE ever materializes.
        VerticalClientUpdates.queueBlockEntityRenderRefresh(minecraft, pos);

        BlockEntityType<?> type = packet.getType();
        BlockState state = level.getBlockState(pos);
        if (!type.isValid(state)) {
            return;
        }

        LevelChunk chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        BlockEntity blockEntity = chunk.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.getType() != type) {
            if (blockEntity != null) {
                chunk.removeBlockEntity(pos);
            }
            blockEntity = type.create(pos, state);
            if (blockEntity == null) {
                return;
            }
            // Vanilla registration installs client tickers as well as placing
            // the instance in LevelChunk's block-entity map.
            chunk.addAndRegisterBlockEntity(blockEntity);
        }

        CompoundTag tag = packet.getTag();
        if (tag != null) {
            blockEntity.loadWithComponents(tag, level.registryAccess());
        }
        ci.cancel();
    }
}
