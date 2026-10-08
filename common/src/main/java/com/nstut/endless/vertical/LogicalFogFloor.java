package com.nstut.endless.vertical;

/** Chooses the world floor for vanilla void fog without changing its fade or colors. */
public final class LogicalFogFloor {
    private LogicalFogFloor() {}

    public static int select(boolean sparseActive, int logicalMinimum, int levelMinimum) {
        return sparseActive ? logicalMinimum : levelMinimum;
    }
}
