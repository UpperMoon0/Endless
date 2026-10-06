package com.nstut.endless.neoforge.compat;

/** Carries the immutable height origin from a culling task into its queued jobs. */
public interface SodiumTaskHeight {
    void endless$setTaskHeight(int sectionY);
}
