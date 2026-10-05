package com.nstut.endless.forge.mixin.compat.embeddium;

import com.nstut.endless.compat.create.DestructionPositionLookup;
import com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
    @Inject(method = "scheduleRebuildForChunk", at = @At("RETURN"))
    private void endless$denseSkyUpdate(int x, int y, int z, boolean important, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null || !EndlessLogicalHeights.isActive() || !level.dimensionType().hasSkyLight()
            || EndlessVerticalEngine.isExtendedY(level, y << 4)) return;
        // Dense block packets and light-engine notifications both reach this
        // renderer entry point, including updates outside the camera window.
        Minecraft mc = Minecraft.getInstance();
        Runnable invalidate = () -> {
            if (mc.level != level) return;
            EndlessVerticalEngine.world(level).invalidateSkyLight();
            // The manager schedules directly, so this does not recurse through us.
            endless$invalidateSkyColumns(x, z);
        };
        // Embeddium accepts off-thread dirty notifications; its snapshot cache
        // and our window/ready set must still be changed on the render thread.
        if (mc.isSameThread()) invalidate.run();
        else mc.execute(invalidate);
    }

    @Redirect(method = "renderBlockEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;asLong()J", remap = true))
    private static long endless$destructionKey(BlockPos pos) {
        return ((DestructionPositionLookup) Minecraft.getInstance().levelRenderer).endless$destructionKey(pos);
    }
}
