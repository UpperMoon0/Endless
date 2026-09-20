package com.nstut.endless.vertical;

import com.nstut.endless.heights.EndlessLogicalHeights;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Global owner for per-Level sparse vertical state. */
public final class EndlessVerticalEngine {
    private static final Map<Level, MinecraftVerticalWorld> WORLDS = new WeakHashMap<>();

    private EndlessVerticalEngine() {}

    public static synchronized MinecraftVerticalWorld world(Level level) {
        return WORLDS.computeIfAbsent(level, MinecraftVerticalWorld::new);
    }

    public static boolean isExtendedY(Level level, int y) {
        return EndlessLogicalHeights.isActive()
            && EndlessLogicalHeights.isSparseBuildHeight(y);
    }

    public static void flushAll() {
        ArrayList<MinecraftVerticalWorld> worlds;
        synchronized (EndlessVerticalEngine.class) {
            worlds = new ArrayList<>(WORLDS.values());
        }
        // Lighting can re-enter world() while holding a world's monitor. Never
        // wait for that monitor while holding the registry monitor.
        for (MinecraftVerticalWorld world : worlds) {
            world.flushDirty();
        }
    }

    public static void unloadColumn(Level level, int chunkX, int chunkZ) {
        MinecraftVerticalWorld world;
        synchronized (EndlessVerticalEngine.class) {
            world = WORLDS.get(level);
        }
        if (world != null) {
            world.unloadColumn(chunkX, chunkZ);
        }
    }

    public static void close(Level level) {
        MinecraftVerticalWorld world;
        synchronized (EndlessVerticalEngine.class) {
            world = WORLDS.remove(level);
        }
        if (world != null) {
            world.close();
        }
    }

    public static void closeAll() {
        ArrayList<MinecraftVerticalWorld> worlds;
        synchronized (EndlessVerticalEngine.class) {
            worlds = new ArrayList<>(WORLDS.values());
            WORLDS.clear();
        }
        for (MinecraftVerticalWorld world : worlds) {
            world.close();
        }
    }
}
