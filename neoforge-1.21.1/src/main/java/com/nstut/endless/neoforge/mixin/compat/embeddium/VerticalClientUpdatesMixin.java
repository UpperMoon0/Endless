package com.nstut.endless.neoforge.mixin.compat.embeddium;

import com.nstut.endless.neoforge.compat.EmbeddiumSnapshotInvalidation;
import com.nstut.endless.vertical.VerticalPageSnapshot;
import org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.nstut.endless.vertical.VerticalClientUpdates", remap = false)
public abstract class VerticalClientUpdatesMixin {
    @Inject(method = "apply", at = @At("RETURN"))
    private static void endless$invalidateSkySnapshots(Minecraft client, VerticalPageSnapshot snapshot, CallbackInfo ci) {
        ((EmbeddiumSnapshotInvalidation) EmbeddiumWorldRenderer.instance())
            .endless$invalidateSkyColumns(snapshot.pos().chunkX(), snapshot.pos().chunkZ());
    }
}
