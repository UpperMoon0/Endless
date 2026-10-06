package com.nstut.endless.forge.testing;

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
final class EmbeddiumCompatibilityRegression {
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
        measure("completeMeshing", () -> verifyCompleteMesh(mc, manager, context, origin));
        measure("chunkLifecycle", () -> verifyChunkRemoval(mc, manager, context, origin));
        if (mc.level.dimensionType().hasSkyLight()) {
            measure("denseRoof", () -> verifyDenseSkyUpdate(mc, origin));
            if (fixtureY >= 320) measure("skyPageBurst", () -> verifyDistantSkyPage(mc, origin));
        }
        System.out.println("ENDLESS_EMBEDDIUM_COMPAT_REGRESSION_PASS sorting=" + cases + " initialMesh/crackAliases/removal denseSkyUpdate/snapshotHalo distantSkyPage=" + (fixtureY >= 320) + " y=" + fixtureY);
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

    private static void verifyChunkRemoval(Minecraft mc, RenderSectionManager manager, ChunkRenderContext context, SectionPos origin) throws Exception {
        var sections = (it.unimi.dsi.fastutil.longs.Long2ReferenceMap<RenderSection>) field(manager, "sectionByPosition");
        var departing = sections.values().stream().filter(section -> section.getChunkX() == origin.getX()
            && section.getChunkZ() == origin.getZ()).toList();
        if (departing.size() != 32) throw new IllegalStateException("Chunk lifecycle fixture has no full render column");
        var render = departing.stream().filter(section -> section.getChunkY() == origin.getY()).findFirst().orElseThrow();
        var cancellation = token();
        var previous = render.getBuildCancellationToken();
        if (previous != null) previous.setCancelled();
        render.setBuildCancellationToken(cancellation);
        var build = new ChunkBuildContext(mc.level, (ChunkVertexType) field(manager, "vertexType"));
        var task = new ChunkBuilderMeshingTask(render, context, 0);
        try {
            // Unload after execution has entered its native section loop. The
            // native node deletion must cancel the task and dispose all nodes.
            var output = task.execute(build, new CancellationToken() {
                int polls;
                public boolean isCancelled() {
                    if (++polls == 2) manager.onChunkRemoved(origin.getX(), origin.getZ());
                    return cancellation.isCancelled();
                }
                public void setCancelled() { cancellation.setCancelled(); }
            });
            if (output != null) { output.delete(); throw new IllegalStateException("Unload did not cancel active native meshing"); }
            if (departing.stream().anyMatch(section -> !section.isDisposed())
                || sections.values().stream().anyMatch(section -> section.getChunkX() == origin.getX() && section.getChunkZ() == origin.getZ())) {
                throw new IllegalStateException("Chunk unload retained native nodes");
            }
            // A late completed job for an unloaded node must never reach GPU upload.
            var stale = new ChunkBuildOutput(render, null, new java.util.HashMap<>(), 0);
            var filter = manager.getClass().getDeclaredMethod("filterChunkBuildResults", java.util.ArrayList.class);
            filter.setAccessible(true);
            try {
                var results = (List<?>) filter.invoke(null, new java.util.ArrayList<>(List.of(stale)));
                if (!results.isEmpty()) throw new IllegalStateException("Disposed node's late result survived upload filtering");
            } finally { stale.delete(); }
        } finally {
            build.cleanup();
            manager.onChunkAdded(origin.getX(), origin.getZ());
        }
        long count = sections.values().stream().filter(section -> section.getChunkX() == origin.getX() && section.getChunkZ() == origin.getZ()).count();
        if (count != 32 || sections.get(SectionPos.asLong(origin.getX(), origin.getY(), origin.getZ())) == render) {
            throw new IllegalStateException("Chunk reload failed to create a fresh bounded column");
        }
        var replacement = sections.get(SectionPos.asLong(origin.getX(), origin.getY(), origin.getZ()));
        if (replacement.isBuilt()) throw new IllegalStateException("Initial-build regression requires a new unbuilt native node");
        manager.scheduleRebuild(origin.getX(), origin.getY(), origin.getZ(), false);
        var pending = (com.nstut.endless.vertical.InitialBuildUpdates) field(manager, "endless$initialUpdates");
        if (!pending.contains(origin.getX(), origin.getY(), origin.getZ()))
            throw new IllegalStateException("Native initial-build edit was dropped");
    }

    private static void verifyDenseSkyUpdate(Minecraft mc, SectionPos origin) throws Exception {
        Object manager = field(SodiumWorldRenderer.instance(), "renderSectionManager");
        var cache = (ClonedChunkSectionCache) field(manager, "sectionCache");
        // Exercise a real dense palette/heightmap edit and vanilla's client
        // dirty notification, including insertion and removal outside the window.
        BlockPos roof = new BlockPos(origin.minBlockX() + 2, 300, origin.minBlockZ() + 2);
        var chunk = mc.level.getChunk(roof);
        var old = chunk.getBlockState(roof);
        var changed = old.isAir() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        var renderMethod = manager.getClass().getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
        renderMethod.setAccessible(true);
        var render = (RenderSection) renderMethod.invoke(manager, origin.getX(), origin.getY(), origin.getZ());
        try {
            for (var state : List.of(changed, old)) {
                var before = cache.acquire(origin.getX(), origin.getY(), origin.getZ());
                if (render != null) render.setPendingUpdate(null);
                var previous = chunk.setBlockState(roof, state, false);
                mc.level.setBlocksDirty(roof, previous, state);
                ((com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation) manager).endless$flushDenseSkyColumns();
                if (before == cache.acquire(origin.getX(), origin.getY(), origin.getZ())
                    || render == null || render.getPendingUpdate() == null) {
                    throw new IllegalStateException("Dense roof edit retained sparse snapshot or omitted mesh rebuild");
                }
            }
            verifyDenseBurst(mc, manager, roof);
            verifyDenseFrameGuard(manager, cache, origin);
            verifyDenseEmptySection(manager, cache, origin);
            if (origin.getY() == -5) verifyDenseRoofValues(mc, manager, cache, origin);
        } finally {
            var previous = chunk.setBlockState(roof, old, false);
            if (previous != null) mc.level.setBlocksDirty(roof, previous, old);
        }
    }

    private static void verifyDenseEmptySection(Object manager, ClonedChunkSectionCache cache, SectionPos origin) throws Exception {
        var window = (VerticalRenderWindow) field(manager, "endless$window");
        var lookup = manager.getClass().getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
        lookup.setAccessible(true);
        for (int y = window.minSection(); y < window.maxSection(); y++) {
            var node = (RenderSection) lookup.invoke(manager, origin.getX(), y, origin.getZ());
            var level = Minecraft.getInstance().level;
            var section = com.nstut.endless.compat.EmbeddiumSections.get(level, level.getChunk(origin.getX(), origin.getZ()), y);
            if (node == null || node.getFlags() != 0 || node.getPendingUpdate() != null || section != null && !section.hasOnlyAir()) continue;
            var before = cache.acquire(origin.getX(), y, origin.getZ());
            var invalidation = (com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation) manager;
            invalidation.endless$queueDenseSkyColumns(origin.getX(), origin.getZ());
            invalidation.endless$flushDenseSkyColumns();
            if (node.getPendingUpdate() != null || before == cache.acquire(origin.getX(), y, origin.getZ()))
                throw new IllegalStateException("Empty dense-batch section rebuilt or retained stale snapshot");
            return;
        }
        throw new IllegalStateException("No settled empty section for dense batching regression");
    }

    private static void verifyDenseFrameGuard(Object manager, ClonedChunkSectionCache cache, SectionPos origin) throws Exception {
        var renderer = SodiumWorldRenderer.instance();
        var invalidation = (com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation) manager;
        var flush = hook(renderer.getClass(), "endless$flushSkyFrame");
        com.nstut.endless.forge.compat.EmbeddiumFrameClock.render(new net.minecraftforge.event.TickEvent.RenderTickEvent(net.minecraftforge.event.TickEvent.Phase.START, 0));
        flush.invoke(renderer, null, null, -1000, false, false, new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("setupTerrain", false));
        var before = cache.acquire(origin.getX(), origin.getY(), origin.getZ());
        invalidation.endless$queueDenseSkyColumns(origin.getX(), origin.getZ());
        flush.invoke(renderer, null, null, -999, false, false, new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("setupTerrain", false));
        if (before != cache.acquire(origin.getX(), origin.getY(), origin.getZ()))
            throw new IllegalStateException("Repeated terrain pass flushed dense columns twice in one frame");
        com.nstut.endless.forge.compat.EmbeddiumFrameClock.render(new net.minecraftforge.event.TickEvent.RenderTickEvent(net.minecraftforge.event.TickEvent.Phase.START, 0));
        flush.invoke(renderer, null, null, -999, false, false, new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("setupTerrain", false));
        if (before == cache.acquire(origin.getX(), origin.getY(), origin.getZ()))
            throw new IllegalStateException("Next frame dropped queued dense columns");
    }

    private static void verifyDenseBurst(Minecraft mc, Object manager, BlockPos roof) {
        var invalidation = (com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation) manager;
        invalidation.endless$flushDenseSkyColumns();
        var chunk = mc.level.getChunk(roof);
        var old = chunk.getBlockState(roof);
        int columns = 0;
        try {
            for (int frame = 0; frame < 120; frame++) {
                // Actual dense palette edits plus repeated light/dirty notifications.
                // Stone/dirt substitutions keep sky exposure unchanged.
                for (int edit = 0; edit < 64; edit++) {
                    var next = (edit % 2 == 0 ? Blocks.STONE : Blocks.DIRT).defaultBlockState();
                    var previous = chunk.setBlockState(roof, next, false);
                    mc.level.setBlocksDirty(roof, previous, next);
                    for (int repeat = 0; repeat < 16; repeat++)
                        SodiumWorldRenderer.instance().scheduleRebuildForChunk(roof.getX() >> 4, roof.getY() >> 4, roof.getZ() >> 4, false);
                }
                int flushed = invalidation.endless$flushDenseSkyColumns();
                if (flushed != 9 || invalidation.endless$flushDenseSkyColumns() != 0)
                    throw new IllegalStateException("Dense burst repeated or dropped column refreshes: " + flushed);
                columns += flushed;
            }
        } finally {
            var previous = chunk.setBlockState(roof, old, false);
            mc.level.setBlocksDirty(roof, previous, old);
            invalidation.endless$flushDenseSkyColumns();
        }
        metrics.put("denseBurstFrames", 120);
        metrics.put("denseBurstEdits", 120 * 64);
        metrics.put("denseBurstNotifications", 120 * 64 * 17);
        metrics.put("denseBurstRefreshedColumns", columns);
    }

    private static void verifyDenseRoofValues(Minecraft mc, Object manager, ClonedChunkSectionCache cache, SectionPos origin) {
        var invalidation = (com.nstut.endless.forge.compat.EmbeddiumSnapshotInvalidation) manager;
        var vertical = EndlessVerticalEngine.world(mc.level);
        var originals = new LinkedHashMap<net.minecraft.world.level.chunk.LevelChunk, LevelChunkSection[]>();
        var heights = new LinkedHashMap<net.minecraft.world.level.chunk.LevelChunk, Map<net.minecraft.world.level.levelgen.Heightmap.Types, long[]>>();
        BlockPos sample = new BlockPos(origin.minBlockX() + 15, origin.minBlockY() + 15, origin.minBlockZ() + 15);
        try {
            // Client-only empty dense core makes the negative sparse sample exposed.
            // Preserve every section and heightmap; the integrated server is untouched.
            for (int x = origin.getX() - 1; x <= origin.getX() + 1; x++) for (int z = origin.getZ() - 1; z <= origin.getZ() + 1; z++) {
                var chunk = mc.level.getChunk(x, z);
                originals.put(chunk, chunk.getSections().clone());
                var saved = new LinkedHashMap<net.minecraft.world.level.levelgen.Heightmap.Types, long[]>();
                for (var entry : chunk.getHeightmaps()) saved.put(entry.getKey(), entry.getValue().getRawData().clone());
                heights.put(chunk, saved);
                for (int i = 0; i < chunk.getSections().length; i++)
                    chunk.getSections()[i] = new LevelChunkSection(mc.level.registryAccess().registryOrThrow(Registries.BIOME));
                for (var height : saved.entrySet()) chunk.setHeightmap(height.getKey(), new long[height.getValue().length]);
            }
            invalidation.endless$queueDenseSkyColumns(origin.getX(), origin.getZ());
            invalidation.endless$flushDenseSkyColumns();
            int exposed = cache.acquire(origin.getX(), origin.getY(), origin.getZ()).getLightArray(LightLayer.SKY).get(15, 15, 15);
            if (exposed <= 0 || vertical.getBrightness(LightLayer.SKY, sample) != exposed)
                throw new IllegalStateException("Dense roof fixture lacks exposed sparse skylight: " + exposed + "/" + vertical.getBrightness(LightLayer.SKY, sample));
            for (boolean insert : new boolean[] {true, false}) {
                // Warm the point cache before edits, so stale cached values fail too.
                vertical.getBrightness(LightLayer.SKY, sample);
                for (var chunk : originals.keySet()) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    var pos = new BlockPos(chunk.getPos().getMinBlockX() + x, 300, chunk.getPos().getMinBlockZ() + z);
                    var next = (insert ? Blocks.STONE : Blocks.AIR).defaultBlockState();
                    var previous = chunk.setBlockState(pos, next, false);
                    mc.level.setBlocksDirty(pos, previous, next);
                }
                invalidation.endless$flushDenseSkyColumns();
                int point = vertical.getBrightness(LightLayer.SKY, sample);
                int snapshot = cache.acquire(origin.getX(), origin.getY(), origin.getZ()).getLightArray(LightLayer.SKY).get(15, 15, 15);
                if (snapshot != point || (insert ? snapshot >= exposed : snapshot != exposed)
                    || vertical.getBrightness(LightLayer.SKY, sample) != point)
                    throw new IllegalStateException("Dense roof skylight/cache mismatch: " + exposed + " -> " + snapshot + "/" + point);
                metrics.put(insert ? "denseRoofInsertedSky" : "denseRoofRemovedSky", snapshot);
            }
            metrics.put("denseRoofExposedSky", exposed);
        } finally {
            for (var entry : originals.entrySet()) {
                System.arraycopy(entry.getValue(), 0, entry.getKey().getSections(), 0, entry.getValue().length);
                for (var height : heights.get(entry.getKey()).entrySet()) entry.getKey().setHeightmap(height.getKey(), height.getValue());
                invalidation.endless$queueDenseSkyColumns(entry.getKey().getPos().x, entry.getKey().getPos().z);
            }
            invalidation.endless$flushDenseSkyColumns();
        }
    }

    private static void verifyDistantSkyPage(Minecraft mc, SectionPos origin) throws Exception {
        Object manager = field(SodiumWorldRenderer.instance(), "renderSectionManager");
        var cache = (ClonedChunkSectionCache) field(manager, "sectionCache");
        var before = cache.acquire(origin.getX(), origin.getY(), origin.getZ());
        int oldSky = before.getLightArray(LightLayer.SKY).get(2, 15, 2);
        var window = (VerticalRenderWindow) field(manager, "endless$window");
        var haloPositions = List.of(SectionPos.of(origin.getX(), window.minSection() - 1, origin.getZ()),
            SectionPos.of(origin.getX(), window.maxSection(), origin.getZ()));
        var haloBefore = haloPositions.stream().map(pos -> cache.acquire(pos.getX(), pos.getY(), pos.getZ())).toList();
        var haloRoof = new java.util.ArrayList<me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection>();
        int roofSection = origin.getY() + 64; // Outside both camera window and local dirty halo.
        var vertical = EndlessVerticalEngine.world(mc.level);
        var originals = new LinkedHashMap<VerticalPagePos, VerticalPageSnapshot>();
        var roof = new LevelChunkSection(mc.level.registryAccess().registryOrThrow(Registries.BIOME));
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) roof.setBlockState(x, 0, z, Blocks.STONE.defaultBlockState());
        try {
            // Cover the entire fifteen-block exposure halo, including neighboring columns.
            for (int x = origin.getX() - 1; x <= origin.getX() + 1; x++) for (int z = origin.getZ() - 1; z <= origin.getZ() + 1; z++) {
                var pagePos = VerticalPagePos.fromChunkAndSection(x, roofSection, z);
                var original = vertical.snapshot(pagePos, false);
                originals.put(pagePos, original);
                var page = new VerticalPage<LevelChunkSection>(pagePos.pageY());
                page.putSection(roofSection, roof);
                VerticalClientUpdates.apply(mc, VerticalPageSnapshot.fromPage(pagePos, original == null ? 0 : original.revision(), page));
            }
            for (int i = 0; i < haloPositions.size(); i++) {
                var pos = haloPositions.get(i);
                var fresh = cache.acquire(pos.getX(), pos.getY(), pos.getZ());
                var sampleHalo = new BlockPos(pos.minBlockX() + 2, pos.minBlockY() + 15, pos.minBlockZ() + 2);
                haloRoof.add(fresh);
                if (fresh == haloBefore.get(i) || (EndlessVerticalEngine.isExtendedY(mc.level, sampleHalo.getY())
                    && fresh.getLightArray(LightLayer.SKY).get(2, 15, 2) != vertical.getBrightness(LightLayer.SKY, sampleHalo))) {
                    throw new IllegalStateException("Distant roof retained stale window-edge halo: " + pos);
                }
            }
            var after = cache.acquire(origin.getX(), origin.getY(), origin.getZ());
            int sky = after.getLightArray(LightLayer.SKY).get(2, 15, 2);
            BlockPos sample = new BlockPos(origin.minBlockX() + 2, origin.minBlockY() + 15, origin.minBlockZ() + 2);
            if (before == after || sky >= oldSky || sky != vertical.getBrightness(LightLayer.SKY, sample)) {
                throw new IllegalStateException("Distant roof page retained a stale visible sky snapshot: " + oldSky + " -> " + sky);
            }
        } finally {
            for (var entry : originals.entrySet()) {
                var pos = entry.getKey();
                VerticalClientUpdates.apply(mc, entry.getValue() == null
                    ? new VerticalPageSnapshot(pos.chunkX(), pos.pageY(), pos.chunkZ(), 0, List.of()) : entry.getValue());
            }
        }
        for (int i = 0; i < haloPositions.size(); i++) {
            var pos = haloPositions.get(i);
            var restored = cache.acquire(pos.getX(), pos.getY(), pos.getZ());
            if (restored == haloRoof.get(i) || restored.getLightArray(LightLayer.SKY).get(2, 15, 2)
                != haloBefore.get(i).getLightArray(LightLayer.SKY).get(2, 15, 2)) {
                throw new IllegalStateException("Roof removal retained stale window-edge halo: " + pos);
            }
        }
        if (cache.acquire(origin.getX(), origin.getY(), origin.getZ()).getLightArray(LightLayer.SKY).get(2, 15, 2) != oldSky) {
            throw new IllegalStateException("Distant roof removal retained stale sky lighting");
        }
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
