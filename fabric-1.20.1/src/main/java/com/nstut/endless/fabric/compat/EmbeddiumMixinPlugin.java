package com.nstut.endless.fabric.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Rubidium shares class names but is not the supported renderer integration. */
public final class EmbeddiumMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        boolean embeddium = FabricLoader.getInstance().isModLoaded("embeddium");
        if (mixin.endsWith("ChunkBuilderSortTaskMixin") || mixin.endsWith("ChunkBuilderMeshingTaskMixin") || mixin.endsWith("RenderSortCameraMixin")) return embeddium;
        return embeddium || FabricLoader.getInstance().isModLoaded("sodium");
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> ours, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
