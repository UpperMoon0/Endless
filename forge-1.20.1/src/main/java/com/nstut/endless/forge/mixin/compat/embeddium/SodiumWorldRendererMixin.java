package com.nstut.endless.forge.mixin.compat.embeddium;

import com.nstut.endless.compat.create.DestructionPositionLookup;
import com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererMixin implements EmbeddiumSnapshotInvalidation {
    @Shadow private RenderSectionManager renderSectionManager;

    @Override public void endless$invalidateSkyColumns(int x, int z) {
        if (renderSectionManager != null) ((EmbeddiumSnapshotInvalidation) renderSectionManager).endless$invalidateSkyColumns(x, z);
    }
    @Redirect(method = "renderBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J", remap = true))
    private static long endless$destructionKey(BlockPos pos) {
        return ((DestructionPositionLookup) Minecraft.getInstance().levelRenderer).endless$destructionKey(pos);
    }
}
