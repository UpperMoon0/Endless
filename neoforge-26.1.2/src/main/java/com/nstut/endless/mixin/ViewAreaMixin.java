package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveRenderProbe;
import com.nstut.endless.vertical.VerticalViewAreaWindow;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera-following 512-block render window for the sparse vertical engine. */
@Mixin(ViewArea.class)
public abstract class ViewAreaMixin implements VerticalViewAreaWindow {
    @Unique private static final int RENDER_WINDOW_SECTIONS = 32;
    @Unique private static final int REBASE_HYSTERESIS_SECTIONS = 8;
    @Unique private static final int UNINITIALIZED = Integer.MIN_VALUE;

    @Shadow @Final protected Level level;
    @Shadow protected int sectionGridSizeX;
    @Shadow protected int sectionGridSizeY;
    @Shadow protected int sectionGridSizeZ;
    @Shadow public SectionRenderDispatcher.RenderSection[] sections;

    @Unique private int endless$windowBaseSection = UNINITIALIZED;

    @Override
    public int endless$getWindowBaseSection() {
        // GraphStorage is first created before LevelRenderer performs the initial
        // camera reposition. In that short bootstrap interval the RenderSections
        // still use the level's dense minimum, so expose that same base. The
        // reposition immediately invalidates the graph and the next GraphStorage
        // observes the real sparse camera-following base.
        return endless$windowBaseSection == UNINITIALIZED
            ? this.level.getMinSectionY()
            : endless$windowBaseSection;
    }

    @Override
    public int endless$getWindowSectionCount() {
        return this.sectionGridSizeY;
    }

    @Unique
    private int endless$sectionIndex(int x, int y, int z) {
        return (z * this.sectionGridSizeY + y) * this.sectionGridSizeX + x;
    }

    @Redirect(
        method = "setViewDistance",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionsCount()I")
    )
    private int endless$capRenderSections(Level level) {
        return EndlessLogicalHeights.isActive()
            ? RENDER_WINDOW_SECTIONS
            : Math.min(level.getSectionsCount(), RENDER_WINDOW_SECTIONS);
    }

    @Inject(method = "repositionCamera", at = @At("HEAD"))
    private void endless$trackCameraSection(SectionPos cameraSectionPos, CallbackInfo ci) {
        int cameraSection = cameraSectionPos.y();
        int minSection = EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.minSection()
            : this.level.getMinSectionY();
        int maxSectionExclusive = EndlessLogicalHeights.isActive()
            ? EndlessLogicalHeights.maxSectionExclusive()
            : this.level.getMaxSectionY() + 1;
        int available = maxSectionExclusive - minSection;

        if (available <= sectionGridSizeY) {
            endless$windowBaseSection = minSection;
            return;
        }

        if (endless$windowBaseSection != UNINITIALIZED) {
            int center = endless$windowBaseSection + (sectionGridSizeY / 2);
            if (Math.abs(cameraSection - center) <= REBASE_HYSTERESIS_SECTIONS) {
                return;
            }
        }

        endless$windowBaseSection = Math.max(minSection, Math.min(
            cameraSection - (sectionGridSizeY / 2),
            maxSectionExclusive - sectionGridSizeY));
    }

    @Redirect(
        method = "repositionCamera",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinSectionY()I")
    )
    private int endless$shiftGridBase(Level level) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            endless$windowBaseSection = level.getMinSectionY();
        }
        return endless$windowBaseSection;
    }

    @Inject(method = "getRenderSection(J)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;", at = @At("HEAD"), cancellable = true)
    private void endless$getRenderSection(
        long sectionNode,
        CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> cir
    ) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            return;
        }
        int ySection = SectionPos.y(sectionNode) - endless$windowBaseSection;
        if (ySection < 0 || ySection >= sectionGridSizeY) {
            cir.setReturnValue(null);
            return;
        }
        int xSection = Mth.positiveModulo(SectionPos.x(sectionNode), sectionGridSizeX);
        int zSection = Mth.positiveModulo(SectionPos.z(sectionNode), sectionGridSizeZ);
        SectionRenderDispatcher.RenderSection renderSection =
            this.sections[this.endless$sectionIndex(xSection, ySection, zSection)];
        if (renderSection.getSectionNode() != sectionNode) {
            cir.setReturnValue(null);
            return;
        }
        LiveRenderProbe.recordViewArea(renderSection.getRenderOrigin());
        cir.setReturnValue(renderSection);
    }

    @Inject(method = "getRenderSectionAt", at = @At("HEAD"), cancellable = true)
    private void endless$getRenderSectionAt(
        BlockPos pos,
        CallbackInfoReturnable<SectionRenderDispatcher.RenderSection> cir
    ) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            return;
        }
        int ySection = Math.floorDiv(pos.getY(), 16) - endless$windowBaseSection;
        if (ySection < 0 || ySection >= sectionGridSizeY) {
            cir.setReturnValue(null);
            return;
        }
        int xSection = Mth.positiveModulo(Math.floorDiv(pos.getX(), 16), sectionGridSizeX);
        int zSection = Mth.positiveModulo(Math.floorDiv(pos.getZ(), 16), sectionGridSizeZ);
        SectionRenderDispatcher.RenderSection renderSection =
            this.sections[this.endless$sectionIndex(xSection, ySection, zSection)];
        long expectedNode = SectionPos.asLong(
            Math.floorDiv(pos.getX(), 16),
            Math.floorDiv(pos.getY(), 16),
            Math.floorDiv(pos.getZ(), 16));
        if (renderSection.getSectionNode() != expectedNode) {
            cir.setReturnValue(null);
            return;
        }
        LiveRenderProbe.recordViewArea(renderSection.getRenderOrigin());
        cir.setReturnValue(renderSection);
    }

    @Inject(method = "setDirty", at = @At("HEAD"), cancellable = true)
    private void endless$setDirty(int x, int y, int z, boolean dirty, CallbackInfo ci) {
        if (endless$windowBaseSection == UNINITIALIZED) {
            return;
        }
        ci.cancel();
        int ySection = y - endless$windowBaseSection;
        if (ySection < 0 || ySection >= sectionGridSizeY) {
            return;
        }
        int xSection = Math.floorMod(x, sectionGridSizeX);
        int zSection = Math.floorMod(z, sectionGridSizeZ);
        this.sections[this.endless$sectionIndex(xSection, ySection, zSection)].setDirty(dirty);
    }
}
