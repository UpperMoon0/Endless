package com.nstut.endless.vertical.lod;

import com.nstut.endless.config.EndlessConfig;
import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.opengl.GL33C;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime opt-in, so ordinary worlds and environmental fog retain their native behavior. */
public final class VerticalLodFogUniform {
    private static final class Binding {
        final int location;
        int value=-1;
        Binding(int program) { location=GL33C.glGetUniformLocation(program,VerticalLodFog.UNIFORM); }
    }
    private static final ConcurrentHashMap<Integer,Binding> bindings=new ConcurrentHashMap<>();
    private VerticalLodFogUniform() {}
    public static boolean enabled() {
        var mc=Minecraft.getInstance();
        if(mc==null || mc.level==null || mc.player==null || mc.gameRenderer==null || !EndlessLogicalHeights.isActive()) return false;
        var config=EndlessConfig.getInstance().getRendering();
        return config.getVerticalLodSections()>config.getVerticalRenderSections()
            && EndlessLogicalHeights.maxSectionExclusive()-EndlessLogicalHeights.minSection()>config.getVerticalRenderSections()
            && mc.gameRenderer.getMainCamera().getFluidInCamera()==FogType.NONE
            && !mc.player.hasEffect(MobEffects.BLINDNESS) && !mc.player.hasEffect(MobEffects.DARKNESS);
    }
    public static void update(int program) {
        if(program<=0) return;
        if(bindings.size()>1024) bindings.clear();
        Binding binding=bindings.computeIfAbsent(program,Binding::new);
        if(binding.location<0) return;
        int value=enabled()?1:0;
        if(binding.value!=value) {
            GL33C.glUniform1i(binding.location,value); binding.value=value;
            if(Boolean.getBoolean("endless.lodMetrics")) System.out.println("ENDLESS_LOD_FOG program="+program+" enabled="+value);
        }
    }
    public static void updateBoundProgram() { update(GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM)); }
    public static void forget(int program) { bindings.remove(program); }
    public static void clear() { bindings.clear(); }
}
