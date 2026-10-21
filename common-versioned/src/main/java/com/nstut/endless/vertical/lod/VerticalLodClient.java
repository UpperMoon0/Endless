package com.nstut.endless.vertical.lod;

import com.nstut.endless.config.EndlessConfig;
import com.nstut.endless.heights.EndlessHeights;
import com.nstut.endless.heights.EndlessLogicalHeights;
import com.nstut.endless.vertical.EndlessVerticalEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4fc;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Render-thread owner. Inventory and mesh construction have independent bounded budgets. */
public final class VerticalLodClient {
    private static final int MAX_ENTRIES = 16384, GPU_BYTES = 32 << 20;
    private static final long BUILD_NANOS = 2_000_000L;
    private record Key(int x,int y,int z) {}
    private static final class Entry {
        LevelChunkSection source;
        VerticalLodGpu.Mesh mesh;
        boolean dirty = true;
        int detail;
        long revision;
    }
    private static final LinkedHashMap<Key,Entry> entries = new LinkedHashMap<>(64,.75f,false);
    private static final VerticalLodGpu gpu = new VerticalLodGpu();
    private static ClientLevel level;
    private static int scan, bytes, frame, pendingVisible;
    private static long lastReport, lastInventory, lastGeneration = -1;
    private static com.nstut.endless.vertical.VerticalRenderWindow lodWindow = new com.nstut.endless.vertical.VerticalRenderWindow();
    private VerticalLodClient() {}

    public static java.util.Map<String,Integer> resourceReceipt() {
        return java.util.Map.of("cachedSections",entries.size(),"retainedMeshBytes",bytes,
            "gpuBytes",gpu.bufferBytes(),"stagingBytes",gpu.stagingBytes(),"pendingVisible",pendingVisible,"depthBytes",gpu.depthBytes());
    }

    public static void setFog(float start,float end,float envStart,float envEnd,float r,float g,float b,float a,boolean linear) {
        syncLevel();
        gpu.setFog(start,end,envStart,envEnd,r,g,b,a,linear);
    }
    public static void setTarget(int color,int depth,int width,int height) {
        syncLevel();
        gpu.setTarget(color,depth,width,height);
    }
    private static void syncLevel() {
        var current=Minecraft.getInstance().level;
        if(level!=current) { resetCache();level=current; }
    }

    public static void invalidateColumn(int x,int z) {
        for(var pair:entries.entrySet()) if(pair.getKey().x==x && pair.getKey().z==z) pair.getValue().dirty=true;
    }

    public static void clear() {
        resetCache();VerticalLodCoverage.clear();
    }
    private static void resetCache() {
        for (var entry : entries.values()) if(entry.mesh != null) entry.mesh.close();
        entries.clear(); bytes=scan=0; level=null; lastGeneration=-1; lastInventory=0;
        lodWindow=new com.nstut.endless.vertical.VerticalRenderWindow(); gpu.close(); pendingVisible=0;
    }

    public static void render(Matrix4fc mvp, double cameraX,double cameraY,double cameraZ) {
        var mc = Minecraft.getInstance();
        if (level != mc.level) { resetCache(); level = mc.level; }
        var config = EndlessConfig.getInstance().getRendering();
        int span = config.getVerticalLodSections();
        if (level == null || !EndlessLogicalHeights.isActive() || span <= config.getVerticalRenderSections()
            || EndlessLogicalHeights.maxSectionExclusive()-EndlessLogicalHeights.minSection()<=config.getVerticalRenderSections()) {
            if (!entries.isEmpty()) clear();
            return;
        }
        int cameraSection=(int)Math.floor(cameraY/16);
        lodWindow.update(cameraSection,EndlessLogicalHeights.minSection(),EndlessLogicalHeights.maxSectionExclusive(),span);
        int first=lodWindow.minSection(),end=lodWindow.maxSection();
        int cx=(int)Math.floor(cameraX/16),cz=(int)Math.floor(cameraZ/16);
        int radius=Math.min(32,mc.options.renderDistance().get()),diameter=radius*2+1;
        long started=System.nanoTime();
        // No full logical-height loops: dense arrays are bounded and sparse sections are indexed.
        boolean inventoryDue = started-lastInventory>=50_000_000L;
        if(inventoryDue) lastInventory=started;
        for(int step=0;step<(inventoryDue?16:0);step++) {
            int index=Math.floorMod(scan++,diameter*diameter);
            int x=cx+index%diameter-radius,z=cz+index/diameter-radius;
            if(!HorizontalLodRange.contains(cameraX,cameraZ,x,z,radius)) continue;
            var chunk=level.getChunkSource().getChunk(x,z,false);
            if(chunk==null) continue;
            Map<Integer,LevelChunkSection> found=new HashMap<>();
            var dense=chunk.getSections();
            int denseMin=EndlessHeights.getDenseMinBuildHeight()>>4;
            for(int i=0;i<dense.length;i++) if(denseMin+i>=first && denseMin+i<end && dense[i]!=null && !dense[i].hasOnlyAir())
                found.put(denseMin+i,dense[i]);
            found.putAll(EndlessVerticalEngine.world(level).lodSections(x,z,first,end));
            // Replaced/empty page snapshots and chunk reloads must remove old GPU geometry.
            for(var iterator=entries.entrySet().iterator();iterator.hasNext();) {
                var pair=iterator.next(); var key=pair.getKey();
                if(key.x==x && key.z==z && !found.containsKey(key.y)) {
                    var mesh=pair.getValue().mesh;
                    if(mesh!=null) { bytes-=mesh.bytes(); mesh.close(); }
                    iterator.remove();
                }
            }
            for(var pair:found.entrySet()) {
                if(VerticalLodCoverage.contains(pair.getKey())) continue;
                var key=new Key(x,pair.getKey(),z);
                Entry entry=entries.computeIfAbsent(key,k->new Entry());
                if(entry.source!=pair.getValue()) { entry.source=pair.getValue(); entry.dirty=true; }
            }
        }
        var world=EndlessVerticalEngine.world(level);
        long generation=world.lodGeneration();
        if(generation!=lastGeneration) {
            for(var pair:entries.entrySet()) {
                var key=pair.getKey(); var entry=pair.getValue();
                if(entry.revision!=world.lodRevision(key.x,key.y,key.z)) entry.dirty=true;
            }
            lastGeneration=generation;
        }
        if(entries.isEmpty()) return;
        int builds=0,draws=0,triangles=0;
        pendingVisible=0;
        var frustum=new org.joml.FrustumIntersection(mvp);
        float sky=(15-level.getSkyDarken())/15f;
        gpu.setAmbient(level.dimensionType().hasSkyLight() ? .1f+.9f*sky*sky : Math.max(.1f,level.dimensionType().ambientLight()));
        gpu.begin(mvp);
        try {
            for(Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();iterator.hasNext();) {
                var pair=iterator.next(); var key=pair.getKey(); var entry=pair.getValue();
                if(key.y<first || key.y>=end || !HorizontalLodRange.contains(cameraX,cameraZ,key.x,key.z,radius)
                    || level.getChunkSource().getChunk(key.x,key.z,false)==null) {
                    if(entry.mesh!=null) { bytes-=entry.mesh.bytes(); entry.mesh.close(); }
                    iterator.remove(); continue;
                }
                // Native coverage always wins, including its hysteresis at page boundaries.
                if(VerticalLodCoverage.contains(key.y)) continue;
                float dx=(float)(key.x*16.0-cameraX),dy=(float)(key.y*16.0-cameraY),dz=(float)(key.z*16.0-cameraZ);
                // The proxy shader extends only the far plane; keep the other five planes.
                if(frustum.intersectAab(dx,dy,dz,dx+16,dy+16,dz+16,31)==org.joml.FrustumIntersection.OUTSIDE) continue;
                int detail=Math.abs((long)key.y-cameraSection)>128?8:4;
                if(entry.detail!=detail) entry.dirty=true;
                if(entry.dirty && builds<2 && (builds==0 || System.nanoTime()-started<BUILD_NANOS)) {
                    if(entry.mesh!=null) { bytes-=entry.mesh.bytes(); entry.mesh.close(); entry.mesh=null; }
                    // Packet application can replace a whole page between inventory scans.
                    // Resolve the current section before rebuilding instead of retaining its old palette.
                    if(EndlessVerticalEngine.isExtendedY(level,key.y<<4))
                        entry.source=EndlessVerticalEngine.world(level).getSectionForRendering(key.x,key.y,key.z);
                    else {
                        var chunk=level.getChunkSource().getChunk(key.x,key.z,false);
                        int index=key.y-(EndlessHeights.getDenseMinBuildHeight()>>4);
                        entry.source=chunk!=null && index>=0 && index<chunk.getSections().length?chunk.getSections()[index]:null;
                    }
                    var pos=new BlockPos.MutableBlockPos();
                    var mesh=VerticalLodMesh.build((x,y,z)-> {
                        if(entry.source==null) return 0;
                        var state=entry.source.getBlockState(x,y,z);
                        if(state.isAir()) return 0;
                        pos.set((key.x<<4)+x,(key.y<<4)+y,(key.z<<4)+z);
                        int color=state.getMapColor(level,pos).col;
                        // Invisible map colors (glass, rails, modded materials) still preserve silhouettes.
                        return 0x10000000 | (state.getLightEmission()<<24) | (color==0?0xaaaaaa:color);
                    },detail);
                    entry.mesh=gpu.upload(mesh); entry.dirty=false; entry.detail=detail;
                    entry.revision=world.lodRevision(key.x,key.y,key.z);
                    bytes+=entry.mesh.bytes(); builds++;
                }
                if(entry.dirty) pendingVisible++;
                if(entry.mesh!=null && gpu.draw(entry.mesh,(key.x*16.0)-cameraX,(key.y*16.0)-cameraY,(key.z*16.0)-cameraZ)) {
                    draws++;triangles+=entry.mesh.vertices()/3;
                }
            }
        } finally { gpu.end(); }
        // Retention is bounded independently from populated world height.
        while(entries.size()>MAX_ENTRIES || bytes>GPU_BYTES) {
            var iterator=entries.entrySet().iterator(); var victim=iterator.next().getValue();
            if(victim.mesh!=null) { bytes-=victim.mesh.bytes(); victim.mesh.close(); }
            iterator.remove();
        }
        frame++;
        if(Boolean.getBoolean("endless.lodMetrics") && System.nanoTime()-lastReport>1_000_000_000L) {
            lastReport=System.nanoTime();
            int pending=0;
            for(var item:entries.entrySet()) if(item.getValue().dirty && !VerticalLodCoverage.contains(item.getKey().y)) pending++;
            System.out.println("ENDLESS_LOD pending="+pending+" known="+entries.size()+" frame="+frame+" sections="+draws+" triangles="+triangles+" gpuBytes="+bytes+" builds="+builds);
        }
    }
}
