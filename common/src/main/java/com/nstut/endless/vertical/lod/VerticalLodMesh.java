package com.nstut.endless.vertical.lod;

import java.util.ArrayList;
import java.util.List;

/** Section-local simplified geometry. Absolute height is never packed into a short. */
public final class VerticalLodMesh {
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1, int rgb) {}
    @FunctionalInterface public interface Colors { int rgb(int x, int y, int z); }
    private final List<Box> boxes;
    private final int samples;

    private VerticalLodMesh(List<Box> boxes, int samples) {
        this.boxes = List.copyOf(boxes);
        this.samples = samples;
    }

    /**
     * Inspect every source cell so a one-block cable cannot disappear between samples.
     * Reduce horizontal detail and merge matching vertical runs, preserving air gaps and
     * the occupied bounds inside each coarse cell. Zero means air; the top byte
     * retains the maximum emission marker (low four bits are the light level).
     */
    public static VerticalLodMesh build(Colors source, int cellSize) {
        if (cellSize != 1 && cellSize != 2 && cellSize != 4 && cellSize != 8 && cellSize != 16)
            throw new IllegalArgumentException("Cell size must divide a section");
        List<Box> result = new ArrayList<>();
        for (int z = 0; z < 16; z += cellSize) for (int x = 0; x < 16; x += cellSize) {
            Box run = null;
            for (int y = 0; y < 16; y++) {
                int x0 = 16, z0 = 16, x1 = 0, z1 = 0, r = 0, g = 0, b = 0, light = 0, count = 0;
                for (int dz = 0; dz < cellSize; dz++) for (int dx = 0; dx < cellSize; dx++) {
                    int color = source.rgb(x + dx, y, z + dz);
                    if (color == 0) continue;
                    x0 = Math.min(x0, x + dx); x1 = Math.max(x1, x + dx + 1);
                    z0 = Math.min(z0, z + dz); z1 = Math.max(z1, z + dz + 1);
                    r += (color >>> 16) & 255; g += (color >>> 8) & 255; b += color & 255; count++;
                    light = Math.max(light, color >>> 24);
                }
                if (count == 0) { if (run != null) result.add(run); run = null; continue; }
                int color = (light << 24) | (r / count << 16) | (g / count << 8) | b / count;
                if (run != null && run.y1 == y && run.x0 == x0 && run.x1 == x1
                    && run.z0 == z0 && run.z1 == z1 && run.rgb == color) {
                    run = new Box(x0, run.y0, z0, x1, y + 1, z1, color);
                } else {
                    if (run != null) result.add(run);
                    run = new Box(x0, y, z0, x1, y + 1, z1, color);
                }
            }
            if (run != null) result.add(run);
        }
        // Greedy rectangular merges keep a solid section at six faces rather than
        // emitting hidden faces for every coarse column. Require identical material
        // and all other bounds; holes and separate islands cannot be merged.
        for (int axis = 0; axis < 2; axis++) {
            final boolean alongX = axis == 0;
            result.sort(java.util.Comparator.comparingInt(Box::rgb).thenComparingInt(Box::y0)
                .thenComparingInt(Box::y1)
                .thenComparingInt(b -> alongX ? b.z0 : b.x0)
                .thenComparingInt(b -> alongX ? b.z1 : b.x1)
                .thenComparingInt(b -> alongX ? b.x0 : b.z0));
            List<Box> merged = new ArrayList<>();
            Box run = null;
            for (Box b : result) {
                boolean same = run != null && run.rgb == b.rgb && run.y0 == b.y0 && run.y1 == b.y1;
                if (same && alongX && run.z0 == b.z0 && run.z1 == b.z1 && run.x1 == b.x0)
                    run = new Box(run.x0,run.y0,run.z0,b.x1,run.y1,run.z1,run.rgb);
                else if (same && !alongX && run.x0 == b.x0 && run.x1 == b.x1 && run.z1 == b.z0)
                    run = new Box(run.x0,run.y0,run.z0,run.x1,run.y1,b.z1,run.rgb);
                else { if (run != null) merged.add(run); run = b; }
            }
            if (run != null) merged.add(run);
            result = merged;
        }
        result.sort(java.util.Comparator.comparingInt(Box::y0).thenComparingInt(Box::z0).thenComparingInt(Box::x0));
        return new VerticalLodMesh(result, 4096);
    }

    public List<Box> boxes() { return boxes; }
    public int samples() { return samples; }
    public int triangles() { return boxes.size() * 12; }
    public int estimatedBytes() { return boxes.size() * 48; }
}
