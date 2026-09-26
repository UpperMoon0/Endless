package com.nstut.endless.mixin;

import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the generic block-entity membership of completed render sections for live regressions. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionCompiledMixin {
    @Inject(method = "setCompiled", at = @At("TAIL"))
    private void endless$recordCompiledBlockEntities(
        SectionRenderDispatcher.CompiledSection compiled, CallbackInfo ci
    ) {
        SectionRenderDispatcher.RenderSection self =
            (SectionRenderDispatcher.RenderSection) (Object) this;
        LiveRenderProbe.recordCompiledSection(self.getOrigin(), compiled.getRenderableBlockEntities());
    }
}
