package com.nstut.endless.fabric.mixin.compat.embeddium;

import com.nstut.endless.vertical.EndlessVerticalEngine;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection", remap = false)
public abstract class ClonedChunkSectionMixin {
    @Inject(method = "copyLightArray", at = @At("HEAD"), cancellable = true)
    private static void endless$snapshotSparseLight(Level world, LightLayer layer, SectionPos section, CallbackInfoReturnable<DataLayer> cir) {
        if (!EndlessLogicalHeights.isActive()) return;
        int y = section.minBlockY();
        boolean boundary = section.getY() == world.getMinSection() || section.getY() == world.getMaxSection() - 1;
        if (!EndlessVerticalEngine.isExtendedY(world, y) && !boundary) return;
        var vertical = EndlessVerticalEngine.world(world);
        if (layer == LightLayer.BLOCK) {
            cir.setReturnValue(vertical.copyRenderBlockLight(section));
            return;
        }
        cir.setReturnValue(vertical.copyRenderSkyLight(section));
    }
}
