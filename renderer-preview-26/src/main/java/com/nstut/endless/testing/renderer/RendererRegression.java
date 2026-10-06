package com.nstut.endless.testing.renderer;
import com.nstut.endless.vertical.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import static com.nstut.endless.testing.renderer.NativeRenderer.*;

/** Native release-renderer tests. Failures are propagated into the capture receipt. */
final class RendererRegression {
    private static final Map<String,Object> metrics = new LinkedHashMap<>();
    static Map<String,Object> measurements() { return new LinkedHashMap<>(metrics); }
    static void queue(Object manager, int x, int z) { call(manager, "endless$queueDenseSkyColumns", x,z); }
    static int flush(Object manager) { return (int)call(manager,"endless$flushDenseSkyColumns"); }
    static void verify(int y) throws Exception {
        metrics.clear();
        if (loaded("embeddium")) {
            var helper=Class.forName("com.nstut.endless.testing.renderer.EmbeddiumSortRegression");
            var check=helper.getDeclaredMethod("verify",int.class);check.setAccessible(true);check.invoke(null,y);
            metrics.put("nativeSortPrecision","initial, dynamic and complete index buffers with failing legacy negative control");
        }
        var mc=Minecraft.getInstance(); var manager=manager(); var origin=SectionPos.of(4,y>>4,4);
        var cache=new Cache(field(manager,"sectionCache"));
        var node=section(manager,4,y>>4,4);
        verifyQueuedHeight(mc, manager, node, origin);
        Object task;
        try { task=call(manager,"createRebuildTask",node.nativeSection,0); }
        catch (IllegalStateException missing) {
            if (!missing.getMessage().startsWith("Missing native method")) throw missing;
            task=call(manager,"createRebuildTask",node.nativeSection,0,false);
        }
        if(task==null) throw new IllegalStateException("Missing full native meshing task");
        var tokenClass=Class.forName(prefix()+".util.task.CancellationToken");
        var token=java.lang.reflect.Proxy.newProxyInstance(tokenClass.getClassLoader(),new Class<?>[]{tokenClass},(proxy,method,args)->method.getReturnType()==boolean.class ? false : null);
        long start=System.nanoTime();
        var output=call(task,"execute",field(call(manager,"getBuilder"),"localContext"),token);
        if(output==null) throw new IllegalStateException("Complete meshing was cancelled");
        try {
            var meshes=(Map<?,?>)field(output,"meshes");
            if(meshes.isEmpty()) throw new IllegalStateException("Complete meshing emitted no geometry");
            long bytes=0;
            for(var mesh:meshes.values()) bytes+=(long)((Number)call(call(mesh,"getVertexData"),"getLength")).longValue();
            if(bytes==0) throw new IllegalStateException("Empty native vertex buffers");
            metrics.put("completeMeshBytes",bytes);
            if (loaded("sodium") && prefix().startsWith("net.caffeinemc")) {
                if (meshes.keySet().stream().noneMatch(pass -> (boolean)call(pass, "isTranslucent"))
                    || field(output, "translucentData") == null)
                    throw new IllegalStateException("Complete native meshing omitted translucent geometry/sort data");
                metrics.put("completeTranslucentData", field(output, "translucentData").getClass().getSimpleName());
            }
            verifyChunkRemoval(manager, node, task, tokenClass, output);
        } finally { destroy(output); }
        metrics.put("completeMeshingMs",(System.nanoTime()-start)/1_000_000.0);
        if(mc.level.dimensionType().hasSkyLight()) {
            verifyDenseBurst(mc,manager,new BlockPos(66,300,66));
            if(y==-80)verifyDenseRoofValues(mc,manager,cache,origin);
            if(y>=320)verifyDistantSkyPage(mc,origin);
        }
    }
    private static void verifyChunkRemoval(Object manager, Section node, Object task, Class<?> tokenClass, Object lateOutput) {
        var departing=sections(manager).stream().filter(n->n.getChunkX()==4&&n.getChunkZ()==4).toList();
        if(departing.size()!=32) throw new IllegalStateException("Unbounded lifecycle column");
        int[] polls = {0};
        boolean[] cancelled = {false};
        var cancellation = java.lang.reflect.Proxy.newProxyInstance(tokenClass.getClassLoader(), new Class<?>[]{tokenClass}, (proxy, method, args) -> {
            if (method.getName().equals("setCancelled")) { cancelled[0] = true; return null; }
            if (method.getName().equals("isCancelled")) {
                if (++polls[0] == 2) call(manager, "onChunkRemoved", 4, 4);
                return cancelled[0] || node.isDisposed();
            }
            return null;
        });
        // Older native APIs attach the worker token directly to the node.
        for (String setter : List.of("setBuildCancellationToken", "setTaskCancellationToken")) {
            try { call(node.nativeSection, setter, cancellation); break; }
            catch (IllegalStateException missing) { if (!missing.getMessage().startsWith("Missing native method")) throw missing; }
        }
        try {
            var cancelledOutput = call(task, "execute", field(call(manager, "getBuilder"), "localContext"), cancellation);
            if (cancelledOutput != null) { destroy(cancelledOutput); throw new IllegalStateException("Active native meshing ignored cancellation"); }
            if (polls[0] < 2 || departing.stream().anyMatch(n->!n.isDisposed()) || section(manager,4,node.getChunkY(),4)!=null)
                throw new IllegalStateException("Chunk unload retained native nodes or did not enter meshing");
            List<?> results;
            try { results = (List<?>)call(manager.getClass(), "filterChunkBuildResults", new ArrayList<>(List.of(lateOutput))); }
            catch (IllegalStateException missing) {
                if (!missing.getMessage().startsWith("Missing native method")) throw missing;
                results = (List<?>)call(manager, "applyBuildOutputs", new ArrayList<>(List.of(lateOutput)));
            }
            if (!results.isEmpty()) throw new IllegalStateException("Disposed section's late result survived native upload filtering");
            metrics.put("activeMeshCancellationPolls", polls[0]);
            metrics.put("lateUploadFiltered", true);
        } finally {call(manager,"onChunkAdded",4,4);}
        if(section(manager,4,node.getChunkY(),4).nativeSection==node.nativeSection) throw new IllegalStateException("Reload reused disposed node");
    }
    private static void verifyQueuedHeight(Minecraft mc, Object manager, Section node, SectionPos origin) throws Exception {
        var frustumType = Class.forName(prefix()+".render.viewport.frustum.Frustum");
        var frustum = java.lang.reflect.Proxy.newProxyInstance(frustumType.getClassLoader(), new Class<?>[]{frustumType},
            (proxy, method, args) -> true);
        var eye = mc.gameRenderer.getMainCamera().position();
        var viewport = Class.forName(prefix()+".render.viewport.Viewport").getConstructors()[0]
            .newInstance(frustum, new org.joml.Vector3d(eye.x, eye.y, eye.z));
        var cullType = Class.forName(prefix()+".render.chunk.occlusion.CullType");
        var wide = cullType.getField("WIDE").get(null);
        var treeClass = Class.forName(prefix()+".render.chunk.lists.TaskCollectingTree");
        var tree = treeClass.getConstructors()[0].newInstance(viewport, 64f, 0, wide, mc.level);
        call(tree, "addPendingSection", node.nativeSection, 8, false);
        var jobs = call(tree, "getPendingTaskLists");
        long decoded = (long) call(jobs, "dequeueNextSectionPos");
        if (decoded != origin.asLong()) throw new IllegalStateException("Queued native mesh job lost its height: " + SectionPos.of(decoded));
        int oldHeight = ((origin.getY() + 128) & 1023) - 128;
        if (Math.abs((long) origin.getY()) > 1024 && oldHeight == origin.getY())
            throw new IllegalStateException("Absolute-height queue negative control did not fail");
        metrics.put("queuedMeshHeight", origin.getY());
        metrics.put("legacyQueuedMeshHeight", oldHeight);
    }
    private static void verifyDenseBurst(Minecraft mc, Object manager, BlockPos roof) {
        var invalidation = manager;
        flush(invalidation);
        var chunk = mc.level.getChunk(roof);
        var old = chunk.getBlockState(roof);
        int columns = 0;
        try {
            for (int frame = 0; frame < 120; frame++) {
                // Actual dense palette edits plus repeated light/dirty notifications.
                // Stone/dirt substitutions keep sky exposure unchanged.
                for (int edit = 0; edit < 64; edit++) {
                    var next = (edit % 2 == 0 ? Blocks.STONE : Blocks.DIRT).defaultBlockState();
                    var previous = chunk.setBlockState(roof, next, 0);
                    mc.level.setBlocksDirty(roof, previous, next);
                    for (int repeat = 0; repeat < 16; repeat++)
                        NativeRenderer.notifyDense(roof.getX() >> 4, roof.getY() >> 4, roof.getZ() >> 4);
                }
                int flushed = flush(invalidation);
                if (flushed != 9 || flush(invalidation) != 0)
                    throw new IllegalStateException("Dense burst repeated or dropped column refreshes: " + flushed);
                columns += flushed;
            }
        } finally {
            var previous = chunk.setBlockState(roof, old, 0);
            mc.level.setBlocksDirty(roof, previous, old);
            flush(invalidation);
        }
        metrics.put("denseBurstFrames", 120);
        metrics.put("denseBurstEdits", 120 * 64);
        metrics.put("denseBurstNotifications", 120 * 64 * 17);
        metrics.put("denseBurstRefreshedColumns", columns);
    }

    private static void verifyDenseRoofValues(Minecraft mc, Object manager, NativeRenderer.Cache cache, SectionPos origin) {
        var invalidation = manager;
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
                    chunk.getSections()[i] = new LevelChunkSection(mc.level.palettedContainerFactory());
                for (var height : saved.entrySet()) chunk.setHeightmap(height.getKey(), new long[height.getValue().length]);
            }
            queue(invalidation, origin.getX(), origin.getZ());
            flush(invalidation);
            int exposed = cache.acquire(origin.getX(), origin.getY(), origin.getZ()).getLightArray(LightLayer.SKY).get(15, 15, 15);
            if (exposed <= 0 || vertical.getBrightness(LightLayer.SKY, sample) != exposed)
                throw new IllegalStateException("Dense roof fixture lacks exposed sparse skylight: " + exposed + "/" + vertical.getBrightness(LightLayer.SKY, sample));
            for (boolean insert : new boolean[] {true, false}) {
                // Warm the point cache before edits, so stale cached values fail too.
                vertical.getBrightness(LightLayer.SKY, sample);
                for (var chunk : originals.keySet()) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    var pos = new BlockPos(chunk.getPos().getMinBlockX() + x, 300, chunk.getPos().getMinBlockZ() + z);
                    var next = (insert ? Blocks.STONE : Blocks.AIR).defaultBlockState();
                    var previous = chunk.setBlockState(pos, next, 0);
                    mc.level.setBlocksDirty(pos, previous, next);
                }
                flush(invalidation);
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
                queue(invalidation, entry.getKey().getPos().x(), entry.getKey().getPos().z());
            }
            flush(invalidation);
        }
    }

    private static void verifyDistantSkyPage(Minecraft mc, SectionPos origin) throws Exception {
        Object manager = field(NativeRenderer.renderer(), "renderSectionManager");
        var cache = new NativeRenderer.Cache(field(manager, "sectionCache"));
        var before = cache.acquire(origin.getX(), origin.getY(), origin.getZ());
        int oldSky = before.getLightArray(LightLayer.SKY).get(2, 15, 2);
        var window = (VerticalRenderWindow) field(manager, "endless$window");
        var haloPositions = List.of(SectionPos.of(origin.getX(), window.minSection() - 1, origin.getZ()),
            SectionPos.of(origin.getX(), window.maxSection(), origin.getZ()));
        var haloBefore = haloPositions.stream().map(pos -> cache.acquire(pos.getX(), pos.getY(), pos.getZ())).toList();
        var haloRoof = new java.util.ArrayList<NativeRenderer.Snapshot>();
        int roofSection = origin.getY() + 64; // Outside both camera window and local dirty halo.
        var vertical = EndlessVerticalEngine.world(mc.level);
        var originals = new LinkedHashMap<VerticalPagePos, VerticalPageSnapshot>();
        var roof = new LevelChunkSection(mc.level.palettedContainerFactory());
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
                if (fresh.value() == haloBefore.get(i).value() || (EndlessVerticalEngine.isExtendedY(mc.level, sampleHalo.getY())
                    && fresh.getLightArray(LightLayer.SKY).get(2, 15, 2) != vertical.getBrightness(LightLayer.SKY, sampleHalo))) {
                    throw new IllegalStateException("Distant roof retained stale window-edge halo: " + pos);
                }
            }
            var after = cache.acquire(origin.getX(), origin.getY(), origin.getZ());
            int sky = after.getLightArray(LightLayer.SKY).get(2, 15, 2);
            BlockPos sample = new BlockPos(origin.minBlockX() + 2, origin.minBlockY() + 15, origin.minBlockZ() + 2);
            if (before.value() == after.value() || sky >= oldSky || sky != vertical.getBrightness(LightLayer.SKY, sample)) {
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
            if (restored.value() == haloRoof.get(i).value() || restored.getLightArray(LightLayer.SKY).get(2, 15, 2)
                != haloBefore.get(i).getLightArray(LightLayer.SKY).get(2, 15, 2)) {
                throw new IllegalStateException("Roof removal retained stale window-edge halo: " + pos);
            }
        }
        if (cache.acquire(origin.getX(), origin.getY(), origin.getZ()).getLightArray(LightLayer.SKY).get(2, 15, 2) != oldSky) {
            throw new IllegalStateException("Distant roof removal retained stale sky lighting");
        }
    }

}
