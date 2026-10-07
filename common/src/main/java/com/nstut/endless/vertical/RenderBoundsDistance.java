package com.nstut.endless.vertical;

/** Distance culling for geometry which extends far from its block origin. */
public final class RenderBoundsDistance {
    private RenderBoundsDistance() {}
    public static boolean within(double x, double y, double z, double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ, double radius) {
        double dx = Math.max(minX - x, Math.max(0, x - maxX));
        double dy = Math.max(minY - y, Math.max(0, y - maxY));
        double dz = Math.max(minZ - z, Math.max(0, z - maxZ));
        return radius > 0 && dx * dx + dy * dy + dz * dz < radius * radius;
    }
}
