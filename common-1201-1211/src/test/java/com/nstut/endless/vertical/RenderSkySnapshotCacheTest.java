package com.nstut.endless.vertical;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderSkySnapshotCacheTest {
    @Test void repeatedClonesShareSolveButNeverMutableNativeSnapshotStorage() {
        var world = new MinecraftVerticalWorld(null);
        var section = SectionPos.of(36, 132, 7);
        var calls = new AtomicInteger();
        java.util.function.Supplier<DataLayer> solve = () -> {
            calls.incrementAndGet(); return new DataLayer(15);
        };
        var first = world.copyRenderSkyLight(section, solve);
        first.set(0, 0, 0, 0);
        assertEquals(15, world.copyRenderSkyLight(section, solve).get(0, 0, 0));
        assertEquals(1, calls.get());
        world.invalidateSkyLight();
        world.copyRenderSkyLight(section, solve);
        assertEquals(2, calls.get());
        world.invalidateLighting(new BlockPos(584, 82, 116));
        world.copyRenderSkyLight(section, solve);
        assertEquals(3, calls.get(), "A roof edit can affect a distant section in the same column");
        world.unloadColumn(36, 7);
        world.copyRenderSkyLight(section, solve);
        assertEquals(4, calls.get());
    }

    @Test void fullCoordinatesAndBoundedEvictionRemainIndependentOfWorldHeight() {
        var world = new MinecraftVerticalWorld(null);
        var calls = new AtomicInteger();
        java.util.function.Supplier<DataLayer> solve = () -> { calls.incrementAndGet(); return new DataLayer(15); };
        world.copyRenderSkyLight(SectionPos.of(0, -500000, 0), solve);
        world.copyRenderSkyLight(SectionPos.of(0, 499999, 0), solve);
        assertEquals(2, calls.get());
        int limit = SectionLightCache.RENDER_SECTION_LIMIT;
        for (int i=0;i<=limit;i++) world.copyRenderSkyLight(SectionPos.of(i, 0, 0), solve);
        world.copyRenderSkyLight(SectionPos.of(0, -500000, 0), solve);
        assertEquals(limit + 4, calls.get());
    }
}
