package com.nstut.endless.neoforge.mixin;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the missing client-only boundary to pinned Create's symmetry renderer methods. */
public final class CreateDistMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (FMLLoader.getDist().isClient() || !mixinClassName.endsWith(".CreateSymmetryMirrorDistMixin")) return;
        // SymmetryMirror's equals/hashCode are valid native value methods. NeoForge's IDE
        // component validation reflects all signatures, including this unannotated renderer.
        // Do not disable validation or change the native mirror's data/geometry semantics.
        targetClass.methods.removeIf(method -> method.name.equals("applyModelTransform")
            && method.desc.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;)V"));
    }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
