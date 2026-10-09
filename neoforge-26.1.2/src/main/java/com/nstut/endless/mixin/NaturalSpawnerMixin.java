package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Validate a selected spawn against logical bounds without widening dense arrays. */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Redirect(method="spawnCategoryForChunk", at=@At(value="INVOKE",
            target="Lnet/minecraft/server/level/ServerLevel;getMinY()I"))
    private static int endless$spawnFloor(ServerLevel level) {
        return EndlessLogicalHeights.isActive() ? EndlessHeights.getMinBuildHeight() : level.getMinY();
    }
}
