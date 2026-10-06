package com.nstut.endless.fabric.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Rubidium shares class names but is not the supported renderer integration. */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (!(FabricLoader.getInstance().isModLoaded("sodium"))) return false;
        String version = FabricLoader.getInstance().getModContainer("sodium").orElseThrow().getMetadata().getVersion().getFriendlyString();
        boolean modern = version.startsWith("0.8.");
        if (mixin.endsWith("RenderSectionManagerModernMixin") || mixin.endsWith("RenderSectionPendingMixin")) return modern;
        if (mixin.endsWith("RenderSectionManagerMixin")) return !modern;
        return true;
    }
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> ours, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
