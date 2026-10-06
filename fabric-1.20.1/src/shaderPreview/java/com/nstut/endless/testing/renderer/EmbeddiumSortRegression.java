package com.nstut.endless.testing.renderer;

import com.nstut.endless.compat.create.DestructionPositionLookup;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import com.nstut.endless.vertical.*;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBufferSorter;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortTask;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer;

/** Runs against the transformed release renderer, including native index buffers. */
final class EmbeddiumSortRegression {
    private static final Map<String, Object> metrics = new LinkedHashMap<>();
    static Map<String, Object> measurements() { return new LinkedHashMap<>(metrics); }
    @FunctionalInterface private interface Probe { void run() throws Exception; }
    private static void measure(String name, Probe probe) throws Exception {
        long start = System.nanoTime(), before = EmbeddiumShaderPreview.allocatedBytes();
        try { probe.run(); }
        finally {
            metrics.put(name + "Ms", (System.nanoTime() - start) / 1_000_000.0);
            long after = EmbeddiumShaderPreview.allocatedBytes();
            metrics.put(name + "RenderThreadBytes", before < 0 || after < 0 ? -1 : after - before);
        }
    }
    static void verify(int fixtureY) throws Exception {
        metrics.clear();
        Minecraft mc = Minecraft.getInstance();
        var state = new TranslucentQuadAnalyzer.SortState(TranslucentQuadAnalyzer.Level.DYNAMIC,
            new float[] {0, .25f, 0, 0, .75f, 0}, null, null);
        var origin = SectionPos.of(4, fixtureY >> 4, 4);
        // Its Fabric view interface is bundled by Embeddium at runtime only.
        var context = (ChunkRenderContext) Class.forName("me.jellysquid.mods.sodium.client.world.WorldSlice")
            .getMethod("prepare", net.minecraft.world.level.Level.class, SectionPos.class, ClonedChunkSectionCache.class)
            .invoke(null, mc.level, origin, new ClonedChunkSectionCache(mc.level));
        if (context == null) throw new IllegalStateException("Missing meshing regression context");
        // Meshing's redirect is invoked on a native task with its real world slice.
        Method meshSort = hook(ChunkBuilderMeshingTask.class, "endless$sort");
        var manager = (RenderSectionManager) field(SodiumWorldRenderer.instance(), "renderSectionManager");
        var cameraField = manager.getClass().getDeclaredField("cameraPosition");
        cameraField.setAccessible(true);
        int cases = 0;
        int nativeNegativeControls = 0;
        for (int y : new int[] {0, 1_000_000, -1_000_000, 7_999_984, -8_000_000}) {
            var render = new RenderSection(null, 0, y >> 4, 0);
            var mesh = new ChunkBuilderMeshingTask(new RenderSection(null, 4, y >> 4, 4), context, 0);
            render.setSortState(state);
            for (double fraction : new double[] {.49, .51}) {
                Vec3 camera = new Vec3(0, y + fraction, 0);
                int expected = fraction < .5 ? 4 : 0;
                Object previousCamera = cameraField.get(manager);
                ChunkBuilderSortTask task;
                try {
                    cameraField.set(manager, camera);
                    task = manager.createSortTask(render, 0);
                } finally { cameraField.set(manager, previousCamera); }
                var output = task.execute(null, null);
                try {
                    int first = output.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT).getIndexData().getDirectBuffer().getInt(0);
                    if (first != expected) throw new IllegalStateException("Dynamic sort lost camera precision at " + camera);
                } finally { output.delete(); }
                var legacy = new NativeBuffer(ChunkBufferSorter.getIndexBufferSize(2));
                try {
                    ChunkBufferSorter.sort(legacy, state, 0, (float) camera.y - y, 0);
                    if (legacy.getDirectBuffer().getInt(0) != expected) nativeNegativeControls++;
                } finally { legacy.free(); }
                // Move the camera relative to the actual fixture section, and
                // verify the independently patched initial mesh sort as well.
                mesh.withCameraPosition(new Vec3(64, y + fraction, 64));
                var buffer = new NativeBuffer(ChunkBufferSorter.getIndexBufferSize(2));
                try {
                    meshSort.invoke(mesh, buffer, state, 0f, 0f, 0f);
                    if (buffer.getDirectBuffer().getInt(0) != expected) throw new IllegalStateException("Initial mesh sort lost camera precision");
                } finally { buffer.free(); }
                cases++;
            }
        }
        // Prove the original absolute-float path cannot distinguish these sides.
        if ((float) (7_999_984 + .49) != (float) (7_999_984 + .51)) throw new IllegalStateException("Invalid precision negative control");
        if (nativeNegativeControls == 0) throw new IllegalStateException("Original native sorting path unexpectedly passed every precision regression");
        BlockPos chest = new BlockPos(66, fixtureY + 1, 67);
        BlockPos alias = chest.offset(0, 4096, 0);
        Method crack = hook(SodiumWorldRenderer.class, "endless$destructionKey");
        var lookup = (DestructionPositionLookup) mc.levelRenderer;
        mc.levelRenderer.destroyBlockProgress(2_000_001, chest, 4);
        mc.levelRenderer.destroyBlockProgress(2_000_002, alias, 7);
        try {
            long first = (long) crack.invoke(null, chest), second = (long) crack.invoke(null, alias);
            if (chest.asLong() != alias.asLong() || first == second
                || first != lookup.endless$destructionKey(chest) || second != lookup.endless$destructionKey(alias)) {
                throw new IllegalStateException("Embeddium block-entity crack lookup aliases full positions");
            }
        } finally {
            mc.levelRenderer.destroyBlockProgress(2_000_001, chest, -1);
            mc.levelRenderer.destroyBlockProgress(2_000_002, alias, -1);
        }
        if ((long) crack.invoke(null, chest) != Long.MIN_VALUE) throw new IllegalStateException("Removed crack retained");
        verifyCompleteMesh(mc, manager, context, origin);
    }

    private static void verifyCompleteMesh(Minecraft mc, RenderSectionManager manager, ChunkRenderContext context, SectionPos origin) throws Exception {
        var build = new ChunkBuildContext(mc.level, (ChunkVertexType) field(manager, "vertexType"));
        int oldFailures = 0;
        try {
            for (double fraction : new double[] {1.49, 1.51}) {
                Vec3 camera = new Vec3(67.5, origin.minBlockY() + fraction, 66.5);
                var task = new ChunkBuilderMeshingTask(new RenderSection(null, origin.getX(), origin.getY(), origin.getZ()), context, 0)
                    .withCameraPosition(camera);
                var output = task.execute(build, token());
                if (output == null) throw new IllegalStateException("Full meshing task unexpectedly cancelled");
                try {
                    var mesh = output.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
                    if (mesh == null || mesh.getIndexData() == null || mesh.getIndexData().getLength() == 0) {
                        throw new IllegalStateException("Full meshing task omitted translucent fixture geometry");
                    }
                    var expected = new NativeBuffer(mesh.getIndexData().getLength());
                    var legacy = new NativeBuffer(mesh.getIndexData().getLength());
                    try {
                        ChunkBufferSorter.sort(expected, mesh.getSortState(),
                            (float) (camera.x - origin.minBlockX()), (float) (camera.y - origin.minBlockY()),
                            (float) (camera.z - origin.minBlockZ()));
                        if (mesh.getIndexData().getDirectBuffer().mismatch(expected.getDirectBuffer()) != -1) {
                            throw new IllegalStateException("Complete meshing output has incorrect initial transparency order");
                        }
                        ChunkBufferSorter.sort(legacy, mesh.getSortState(),
                            (float) camera.x - origin.minBlockX(), (float) camera.y - origin.minBlockY(),
                            (float) camera.z - origin.minBlockZ());
                        if (legacy.getDirectBuffer().mismatch(expected.getDirectBuffer()) != -1) oldFailures++;
                    } finally { expected.free(); legacy.free(); }
                } finally { output.delete(); }
            }
        } finally { build.cleanup(); }
        if (Math.abs(origin.minBlockY()) >= 1_000_000 && oldFailures == 0) {
            throw new IllegalStateException("Full-mesh precision fixture failed to expose the old sorter");
        }
    }

    private static CancellationToken token() {
        return new CancellationToken() {
            private boolean cancelled;
            public boolean isCancelled() { return cancelled; }
            public void setCancelled() { cancelled = true; }
        };
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Method hook(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) if (method.getName().contains(name)) {
            method.setAccessible(true);
            return method;
        }
        throw new IllegalStateException("Renderer compatibility hook missing: " + name);
    }
}
