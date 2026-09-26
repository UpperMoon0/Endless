package com.nstut.endless.mixin;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes coordinates outside the dense v0.4 core to sparse vertical pages. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Invoker("updateBlockEntityTicker")
    protected abstract void endless$updateBlockEntityTicker(BlockEntity blockEntity);

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void endless$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (EndlessVerticalEngine.isExtendedY(self.getLevel(), pos.getY())) {
            cir.setReturnValue(EndlessVerticalEngine.world(self.getLevel()).getBlockState(pos));
        }
    }

    @Inject(method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;",
        at = @At("HEAD"), cancellable = true)
    private void endless$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (EndlessVerticalEngine.isExtendedY(self.getLevel(), pos.getY())) {
            cir.setReturnValue(EndlessVerticalEngine.world(self.getLevel()).getFluidState(pos));
        }
    }

    @Inject(method = "getFluidState(III)Lnet/minecraft/world/level/material/FluidState;",
        at = @At("HEAD"), cancellable = true)
    private void endless$getFluidState(int x, int y, int z, CallbackInfoReturnable<FluidState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (EndlessVerticalEngine.isExtendedY(self.getLevel(), y)) {
            cir.setReturnValue(EndlessVerticalEngine.world(self.getLevel()).getFluidState(new BlockPos(x, y, z)));
        }
    }

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void endless$setBlockState(
        BlockPos pos,
        BlockState state,
        int flags,
        CallbackInfoReturnable<BlockState> cir
    ) {
        LevelChunk self = (LevelChunk) (Object) this;
        Level level = self.getLevel();
        if (!EndlessVerticalEngine.isExtendedY(level, pos.getY())) {
            return;
        }

        MinecraftVerticalWorld vertical = EndlessVerticalEngine.world(level);
        BlockState oldState = vertical.setBlockState(pos, state);
        if (oldState == state) {
            cir.setReturnValue(null);
            return;
        }

        boolean blockChanged = !oldState.is(state.getBlock());
        boolean movedByPiston = (flags & 0x40) != 0;
        boolean sideEffects = (flags & 0x100) == 0;

        if (blockChanged && oldState.hasBlockEntity()
            && !state.shouldChangedStateKeepBlockEntity(oldState)) {
            if (!level.isClientSide() && sideEffects) {
                BlockEntity oldBlockEntity = self.getBlockEntity(pos);
                if (oldBlockEntity != null) {
                    oldBlockEntity.preRemoveSideEffects(pos, oldState);
                }
            }
            self.removeBlockEntity(pos);
        }

        if ((blockChanged || state.getBlock() instanceof BaseRailBlock)
            && level instanceof ServerLevel serverLevel
            && ((flags & 1) != 0 || movedByPiston)) {
            oldState.affectNeighborsAfterRemoval(serverLevel, pos, movedByPiston);
        }

        if (!level.isClientSide() && (flags & 0x200) == 0) {
            state.onPlace(level, pos, oldState, movedByPiston);
        }

        if (state.hasBlockEntity()) {
            BlockEntity blockEntity = self.getBlockEntity(pos);
            if (blockEntity != null && !blockEntity.isValidBlockState(state)) {
                self.removeBlockEntity(pos);
                blockEntity = null;
            }
            if (blockEntity == null) {
                blockEntity = ((EntityBlock) state.getBlock()).newBlockEntity(pos, state);
                if (blockEntity != null) {
                    self.addAndRegisterBlockEntity(blockEntity);
                }
            } else {
                blockEntity.setBlockState(state);
                endless$updateBlockEntityTicker(blockEntity);
            }
        }

        self.markUnsaved();
        cir.setReturnValue(oldState);
    }

}
