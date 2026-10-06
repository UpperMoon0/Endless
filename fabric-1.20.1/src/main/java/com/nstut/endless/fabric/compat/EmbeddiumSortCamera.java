package com.nstut.endless.fabric.compat;

import net.minecraft.world.phys.Vec3;

/** Preserve the task's immutable camera snapshot until section-relative conversion. */
public interface EmbeddiumSortCamera {
    void endless$setSortCamera(Vec3 camera);
}
