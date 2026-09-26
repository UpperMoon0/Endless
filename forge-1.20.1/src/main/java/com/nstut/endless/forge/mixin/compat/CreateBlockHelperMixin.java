package com.nstut.endless.forge.mixin.compat;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.vertical.MinecraftVerticalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.utility.BlockHelper", remap = false)
public abstract class CreateBlockHelperMixin {
    private static final int CREATE_RAIL_UPDATE_FLAGS = 82;
    private static final int CREATE_RAIL_RECURSION_LIMIT = 512;

    @Inject(method = "placeRailWithoutUpdate", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private static void endless$placeSparseRail(Level world, BlockState state, BlockPos target, CallbackInfo ci) {
        if (!EndlessVerticalEngine.isExtendedY(world, target.getY())) {
            return;
        }

        LevelChunk chunk = world.getChunkAt(target);
        MinecraftVerticalWorld vertical = EndlessVerticalEngine.world(world);
        BlockState oldState = vertical.setBlockState(target, state);
        chunk.setUnsaved(true);

        world.markAndNotifyBlock(target, chunk, oldState, state, CREATE_RAIL_UPDATE_FLAGS, CREATE_RAIL_RECURSION_LIMIT);

        world.setBlock(target, state, CREATE_RAIL_UPDATE_FLAGS);
        world.neighborChanged(target, world.getBlockState(target.below()).getBlock(), target.below());
        ci.cancel();
    }
}