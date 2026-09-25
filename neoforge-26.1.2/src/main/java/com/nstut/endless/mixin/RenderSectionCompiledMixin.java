package com.nstut.endless.mixin;

import com.nstut.endless.testing.LiveRenderProbe;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records generic block-entity membership whenever a 26.x render section installs a compiled mesh. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionCompiledMixin {
    @Inject(method = "setSectionMesh", at = @At("TAIL"))
    private void endless$recordCompiledBlockEntities(
        SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir
    ) {
        SectionRenderDispatcher.RenderSection self =
            (SectionRenderDispatcher.RenderSection) (Object) this;
        LiveRenderProbe.recordCompiledSection(
            self.getRenderOrigin(), sectionMesh.getRenderableBlockEntities());
    }
}