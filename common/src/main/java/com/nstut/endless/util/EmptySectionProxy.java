package com.nstut.endless.util;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

public final class EmptySectionProxy {
    public static boolean hasOnlyAir() {
        return true;
    }

    public static BlockState getBlockState() {
        return Blocks.AIR.defaultBlockState();
    }

    public static FluidState getFluidState() {
        return getBlockState().getFluidState();
    }

    public static boolean isRandomlyTicking() {
        return false;
    }

    public static boolean isRandomlyTickingBlocks() {
        return false;
    }

    public static boolean isRandomlyTickingFluids() {
        return false;
    }

    public static short nonEmptyBlockCount() {
        return 0;
    }

    public static short tickingBlockCount() {
        return 0;
    }

    public static short tickingFluidCount() {
        return 0;
    }
}
