package com.nstut.endless.testing.renderer;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Development-only bridge; all calls operate the actual selected release renderer. */
public final class NativeRenderer {
    private NativeRenderer() {}
    public static void createFreshWorld(String name, net.minecraft.world.level.LevelSettings settings,
            net.minecraft.world.level.levelgen.WorldOptions options,
            java.util.function.Function<net.minecraft.core.RegistryAccess,net.minecraft.world.level.levelgen.WorldDimensions> dimensions) {
        var flows=Minecraft.getInstance().createWorldOpenFlows();
        try { call(flows,"createFreshLevel",name,settings,options,dimensions); }
        catch(IllegalStateException missing) {
            if(!missing.getMessage().startsWith("Missing native method")) throw missing;
            call(flows,"createFreshLevel",name,settings,options,dimensions,null);
        }
    }
    public static boolean loaded(String id) {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            return (boolean) loader.getMethod("isModLoaded", String.class).invoke(loader.getMethod("getInstance").invoke(null), id);
        } catch (ClassNotFoundException absent) {
            try {
                Class<?> loader = Class.forName("net.neoforged.fml.ModList");
                return (boolean) loader.getMethod("isLoaded", String.class).invoke(loader.getMethod("get").invoke(null), id);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public static boolean exists(String name) { try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; } }
    private static String backendPrefix;
    public static String prefix() {
        if (backendPrefix == null) backendPrefix = detectPrefix();
        return backendPrefix;
    }
    private static String detectPrefix() {
        if (loaded("embeddium") && exists("org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer")) return "org.embeddedt.embeddium.impl";
        return exists("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer") ? "net.caffeinemc.mods.sodium.client" : "me.jellysquid.mods.sodium.client";
    }
    private static Method rendererInstance, denseNotify;
    public static Object renderer() {
        try {
            if (rendererInstance == null) {
                Class<?> type=Class.forName(prefix()+".render."+(prefix().startsWith("org.embeddedt") ? "EmbeddiumWorldRenderer" : "SodiumWorldRenderer"));
                rendererInstance=type.getDeclaredMethod("instance");rendererInstance.setAccessible(true);
                denseNotify=type.getDeclaredMethod("scheduleRebuildForChunk",int.class,int.class,int.class,boolean.class);denseNotify.setAccessible(true);
            }
            return rendererInstance.invoke(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public static Object manager() { return field(renderer(), "renderSectionManager"); }
    public static void arm(Runnable callback) {
        String loader = exists("net.fabricmc.loader.api.FabricLoader") ? "fabric" : "neoforge";
        try { Class.forName("com.nstut.endless." + loader + ".compat.EmbeddiumFrameClock").getField("afterFrame").set(null, callback); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try { var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
            catch (NoSuchFieldException next) {} catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        throw new IllegalStateException("Missing native field " + target.getClass() + "." + name);
    }
    public static Object call(Object target, String name, Object... args) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (var m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length || m.isBridge()) continue;
            boolean match = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> p = m.getParameterTypes()[i];
                if (args[i] != null && !p.isPrimitive() && !p.isInstance(args[i])) match = false;
            }
            if (!match) continue;
            try { m.setAccessible(true); return m.invoke(target instanceof Class<?> ? null : target, args); }
            catch (InvocationTargetException e) { throw new IllegalStateException("Native " + name, e.getCause()); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        throw new IllegalStateException("Missing native method " + type + "." + name);
    }
    public static Object optional(Object target, String name) {
        try { return call(target, name); } catch (IllegalStateException e) {
            if (e.getMessage().startsWith("Missing native method")) return null;
            throw e;
        }
    }
    public static void notifyDense(int x, int y, int z) {
        Object renderer=renderer();
        try { denseNotify.invoke(renderer,x,y,z,false); }
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static Section section(Object manager, int x, int y, int z) {
        Object value = call(manager, "getRenderSection", x, y, z);
        return value == null ? null : new Section(value);
    }
    public static List<Section> sections(Object manager) {
        Collection<?> values;
        try { values = ((Map<?, ?>) field(manager, "sectionByPosition")).values(); }
        catch (IllegalStateException absent) {
            Object storage = field(manager, "renderSections");
            var list=new ArrayList<Object>();
            var window=(com.nstut.endless.vertical.VerticalRenderWindow)field(manager,"endless$window");
            var chunks=(it.unimi.dsi.fastutil.longs.LongSet)field(manager,"endless$readyChunks");
            for(long key:chunks) for(int y=window.minSection();y<window.maxSection();y++) {
                Object node=call(storage,"getCurrent",net.minecraft.core.SectionPos.asLong((int)key,y,(int)(key>>>32)));
                if(node!=null)list.add(node);
            }
            if(list.size()!=((Number)call(storage,"size")).intValue())throw new IllegalStateException("Native storage contains unexpected nodes");
            values=list;
        }
        return values.stream().map(Section::new).toList();
    }
    public static final class Section {
        public final Object nativeSection;
        public Section(Object value) { nativeSection = value; }
        public int getChunkX() { return (int) call(nativeSection, "getChunkX"); }
        public int getChunkY() { return (int) call(nativeSection, "getChunkY"); }
        public int getChunkZ() { return (int) call(nativeSection, "getChunkZ"); }
        public boolean isBuilt() { return (boolean) call(nativeSection, "isBuilt"); }
        public boolean isDisposed() { return (boolean) call(nativeSection, "isDisposed"); }
        public void setPendingUpdate(Object value) { if (hasMethod(nativeSection, "clearPendingUpdate")) call(nativeSection, "clearPendingUpdate"); else call(nativeSection, "setPendingUpdate", value); }
        public Object getPendingUpdate() { Object v = call(nativeSection, "getPendingUpdate"); return v instanceof Integer n && n == 0 ? null : v; }
        public Object getBuildCancellationToken() {
            for (String method : List.of("getBuildCancellationToken", "getTaskCancellationToken", "getRunningJob")) {
                Object value = optional(nativeSection, method); if (value != null) return value;
            }
            try { var jobs = (List<?>) field(nativeSection, "runningJobs"); return jobs.isEmpty() ? null : jobs; }
            catch (IllegalStateException missing) { return null; }
        }
        public Region getRegion() { Object v = call(nativeSection, "getRegion"); return v == null ? null : new Region(v); }
    }
    public record Region(Object value) { public Object getResources() { return optional(value, "getResources"); } }
    public static int workerCount(Object manager) { return (int) call(call(manager, "getBuilder"), "getTotalThreadCount"); }
    public static final class Cache {
        public final Object value;
        public Cache(Level level) {
            try { value = Class.forName(prefix() + ".world.cloned.ClonedChunkSectionCache").getConstructor(Level.class).newInstance(level); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        public Cache(Object nativeCache) { value = nativeCache; }
        public Snapshot acquire(int x, int y, int z) { return new Snapshot(call(value, "acquire", x, y, z)); }
    }
    public record Snapshot(Object value) {
        public BlockState state(int x, int y, int z) { return (BlockState) call(call(value, "getBlockData"), "get", x, y, z); }
        public DataLayer getLightArray(LightLayer layer) { return (DataLayer) call(value, "getLightArray", layer); }
        @SuppressWarnings("unchecked") public Map<?, BlockEntity> getBlockEntityMap() { return (Map<?, BlockEntity>) call(value, "getBlockEntityMap"); }
    }
    public static void destroy(Object output) { if (output != null) { if (hasMethod(output, "destroy")) call(output, "destroy"); else call(output, "delete"); } }
    private static boolean hasMethod(Object value, String name) { return Arrays.stream(value.getClass().getMethods()).anyMatch(m -> m.getName().equals(name)); }
}
