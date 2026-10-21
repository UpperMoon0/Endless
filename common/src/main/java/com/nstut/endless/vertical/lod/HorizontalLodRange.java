package com.nstut.endless.vertical.lod;

/** A taller proxy budget never expands the horizontal block-render radius. */
public final class HorizontalLodRange {
    private HorizontalLodRange() {}
    public static boolean contains(double cameraX, double cameraZ, int chunkX, int chunkZ, int chunks) {
        double x = chunkX * 16.0, z = chunkZ * 16.0;
        double dx = Math.max(0, Math.max(x - cameraX, cameraX - (x + 16)));
        double dz = Math.max(0, Math.max(z - cameraZ, cameraZ - (z + 16)));
        double radius = chunks * 16.0;
        return dx * dx + dz * dz < radius * radius;
    }
}
