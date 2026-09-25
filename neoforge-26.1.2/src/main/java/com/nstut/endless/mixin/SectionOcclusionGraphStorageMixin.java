package com.nstut.endless.mixin;

import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.testing.LiveRenderProbe;
import com.nstut.endless.vertical.VerticalViewAreaWindow;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the visibility tree on the exact same vertical window as ViewArea. */
@Mixin(targets = "net.minecraft.client.renderer.SectionOcclusionGraph$GraphStorage")
public abstract class SectionOcclusionGraphStorageMixin {
    @Unique
    private static final ThreadLocal<WindowGeometry> endless$constructingWindow = new ThreadLocal<>();

    @Inject(method = "<init>", at = @At("HEAD"))
    private static void endless$captureViewAreaWindow(ViewArea viewArea, CallbackInfo ci) {
        if (!EndlessLogicalHeights.isActive() || !(viewArea instanceof VerticalViewAreaWindow window)) {
            endless$constructingWindow.remove();
            return;
        }
        endless$constructingWindow.set(new WindowGeometry(
            window.endless$getWindowBaseSection(), window.endless$getWindowSectionCount()));
    }

    @ModifyArg(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Octree;<init>(Lnet/minecraft/core/SectionPos;III)V"),
        index = 3
    )
    private int endless$alignTreeBaseToViewArea(
        SectionPos cameraSection, int renderDistance, int sectionsPerChunk, int minBlockY
    ) {
        if (!EndlessLogicalHeights.isActive()) {
            return minBlockY;
        }

        WindowGeometry window = endless$constructingWindow.get();
        if (window == null) {
            return minBlockY;
        }

        int treeSectionCount = Mth.smallestEncompassingPowerOfTwo(renderDistance * 2 + 1);
        int treeBaseSection;
        if (treeSectionCount >= sectionsPerChunk) {
            // 26.1's Octree uses minBlockY whenever its power-of-two root is at
            // least as tall as ViewArea. Anchor that root to ViewArea's *actual*
            // moving base rather than independently to camera-renderDistance.
            // This keeps all 32 render sections in-tree even while ViewArea is
            // intentionally held by its 8-section rebase hysteresis.
            treeBaseSection = window.baseSection();
        } else {
            // Vanilla ignores minBlockY in this branch and centers the smaller
            // tree on the camera. Record that real geometry for diagnostics/tests.
            treeBaseSection = cameraSection.y() - renderDistance;
        }

        LiveRenderProbe.recordRenderWindowAlignment(
            cameraSection.y(), window.baseSection(), window.sectionCount(),
            treeBaseSection, treeSectionCount);
        return treeBaseSection * 16;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private static void endless$clearViewAreaWindow(ViewArea viewArea, CallbackInfo ci) {
        endless$constructingWindow.remove();
    }

    @Unique
    private record WindowGeometry(int baseSection, int sectionCount) {}
}
