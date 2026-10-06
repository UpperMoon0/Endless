package com.nstut.endless.neoforge.mixin.compat.sodium;

import com.nstut.endless.neoforge.compat.SodiumPendingUpdate;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", remap = false)
public abstract class RenderSectionPendingMixin implements SodiumPendingUpdate {
    @Shadow private int pendingUpdateType;
    @Override public boolean endless$hasPendingUpdate() { return pendingUpdateType != 0; }
}
