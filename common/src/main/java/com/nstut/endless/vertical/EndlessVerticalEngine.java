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

    public static synchronized void flushAll() {
        for (MinecraftVerticalWorld world : new ArrayList<>(WORLDS.values())) {
            world.flushDirty();
        }
    }

    public static synchronized void unloadColumn(Level level, int chunkX, int chunkZ) {
        MinecraftVerticalWorld world = WORLDS.get(level);
        if (world != null) {
            world.unloadColumn(chunkX, chunkZ);
        }
    }

    public static synchronized void close(Level level) {
        MinecraftVerticalWorld world = WORLDS.remove(level);
        if (world != null) {
            world.close();
        }
    }

    public static synchronized void closeAll() {
        for (MinecraftVerticalWorld world : new ArrayList<>(WORLDS.values())) {
            world.close();
        }
        WORLDS.clear();
    }
}
