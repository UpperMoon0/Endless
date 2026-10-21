package com.nstut.endless.vertical.lod;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Inspect optional bytecode without loading it before Mixin can transform it. */
public final class LodFogMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String target,String mixin) {
        boolean modern=mixin.endsWith(".compat.SodiumModernLodFogSourceMixin");
        boolean legacy=mixin.endsWith(".compat.SodiumLodFogSourceMixin");
        if(!modern && !legacy) return true;
        try {
            var node=MixinService.getService().getBytecodeProvider().getClassNode(target,false);
            boolean hasModern=node.methods.stream().anyMatch(method->method.name.equals("parseShader")
                && method.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V") && (method.access&Opcodes.ACC_STATIC)==0);
            return modern==hasModern;
        } catch(java.io.IOException|ClassNotFoundException absent) { return false; }
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> ours,Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info) { }
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info) { }
}
