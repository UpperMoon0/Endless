package com.nstut.endless.prediction;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Mutable state for one extended-Y client prediction entry.
 *
 * <p>This intentionally lives outside the Mixin package. Mixin merges fields
 * and handlers into the target class, so any helper type referenced by the
 * merged bytecode must be an ordinary loadable class rather than a class owned
 * by the mixin package.</p>
 */
public final class ExtendedPredictedState {
    private int sequence;
    private BlockState state;
    private final Vec3 playerPos;

    public ExtendedPredictedState(int sequence, BlockState state, Vec3 playerPos) {
        this.sequence = sequence;
        this.state = state;
        this.playerPos = playerPos;
    }

    public int sequence() {
        return sequence;
    }

    public BlockState state() {
        return state;
    }

    public Vec3 playerPos() {
        return playerPos;
    }

    public void updateSequence(int sequence) {
        this.sequence = sequence;
    }

    public void updateState(BlockState state) {
        this.state = state;
    }
}
