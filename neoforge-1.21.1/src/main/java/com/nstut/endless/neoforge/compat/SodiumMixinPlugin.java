package com.nstut.endless.neoforge.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Rubidium shares class names but is not the supported renderer integration. */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (!(LoadingModList.get().getModFileById("sodium") != null)) return false;
        String version = LoadingModList.get().getModFileById("sodium").getMods().stream().filter(mod -> mod.getModId().equals("sodium")).findFirst().orElseThrow().getVersion().toString();
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
