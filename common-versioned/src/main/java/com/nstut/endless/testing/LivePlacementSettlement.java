package com.nstut.endless.testing;

/** Bounded settling of a live test placement after its own prediction acknowledgement. */
final class LivePlacementSettlement {
    static final int SETTLE_TICKS = 40;
    static final int MAX_ATTEMPTS = 3;

    enum Result { WAIT, ACCEPTED, RETRY, REJECTED }

    private int attempts;
    private int ackBaseline;
    private int acknowledgedTick = -1;

    void dispatched(int acknowledgements) {
        attempts++;
        ackBaseline = acknowledgements;
        acknowledgedTick = -1;
    }

    int attempts() {
        return attempts;
    }

    Result poll(int tick, int acknowledgements, boolean stone, boolean air) {
        // An earlier attempt's acknowledgement cannot certify optimistic stone
        // from a retry. Every dispatch must reconcile independently.
        if (acknowledgements <= ackBaseline) return Result.WAIT;
        if (stone) return Result.ACCEPTED;
        if (acknowledgedTick < 0) acknowledgedTick = tick;
        if (tick - acknowledgedTick < SETTLE_TICKS) return Result.WAIT;
        // Teleport/page synchronization may briefly restore AIR. Only retry an
        // empty target, and never turn a persistent rejection into a pass.
        return air && attempts < MAX_ATTEMPTS ? Result.RETRY : Result.REJECTED;
    }
}
