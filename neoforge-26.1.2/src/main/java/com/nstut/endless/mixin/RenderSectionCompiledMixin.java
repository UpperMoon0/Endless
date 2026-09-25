package com.nstut.endless.mixin;

import com.nstut.endless.debug.EndlessDebugTrace;
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
        if (EndlessDebugTrace.highY(self.getRenderOrigin().getY())
            && !sectionMesh.getRenderableBlockEntities().isEmpty()) {
            StringBuilder details = new StringBuilder("origin=")
                .append(self.getRenderOrigin())
                .append(" beCount=").append(sectionMesh.getRenderableBlockEntities().size());
            sectionMesh.getRenderableBlockEntities().forEach(blockEntity -> details
                .append(" | pos=").append(blockEntity.getBlockPos())
                .append(" type=").append(blockEntity.getType())
                .append(" cachedState=").append(blockEntity.getBlockState())
                .append(" validCached=").append(blockEntity.getType().isValid(blockEntity.getBlockState()))
                .append(" hasLevel=").append(blockEntity.hasLevel())
                .append(" removed=").append(blockEntity.isRemoved()));
            EndlessDebugTrace.state("compiled:" + self.getRenderOrigin(),
                "BE_SECTION_COMPILED", details.toString());
        }
    }
}