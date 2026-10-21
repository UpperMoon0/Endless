package com.nstut.endless.vertical.lod;

/** Last native terrain window published by the active renderer, on the render thread. */
public final class VerticalLodCoverage {
    private static int min, max;
    private VerticalLodCoverage() {}
    public static void publish(int first, int end) { min = first; max = end; }
    public static boolean contains(int sectionY) { return sectionY >= min && sectionY < max; }
    public static void clear() { min = max = 0; }
}
