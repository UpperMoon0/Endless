package com.nstut.endless.mixin.accessor;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface VisibleSectionsAccessor {
    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> endless$getVisibleSections();
}
