package com.nstut.endless.vertical;

/** Bounded, section-aligned camera window; never scales with the logical height. */
public final class VerticalRenderWindow {
    public static final int MAX_SECTIONS = VerticalViewDistance.MAX_SECTIONS;
    private static final int HYSTERESIS = VerticalViewDistance.HYSTERESIS_SECTIONS;
    private int min;
    private int max;
    private boolean initialized;

    public boolean update(int cameraSection, int logicalMin, int logicalMax) {
        return update(cameraSection, logicalMin, logicalMax,
            com.nstut.endless.config.EndlessConfig.getInstance().getRendering().getVerticalRenderSections());
    }

    public boolean update(int cameraSection, int logicalMin, int logicalMax, int sectionBudget) {
        if (logicalMax <= logicalMin) throw new IllegalArgumentException("Empty render range");
        int size = Math.min(VerticalViewDistance.clampSections(sectionBudget), logicalMax - logicalMin);
        if (initialized && max - min == size && min >= logicalMin && max <= logicalMax
            && (size == logicalMax - logicalMin || Math.abs((long) cameraSection - (min + size / 2)) <= HYSTERESIS)) {
            return false;
        }
        int next = Math.max(logicalMin, Math.min(cameraSection - size / 2, logicalMax - size));
        boolean changed = !initialized || next != min || next + size != max;
        min = next;
        max = next + size;
        initialized = true;
        return changed;
    }

    public boolean initialized() { return initialized; }
    public int minSection() { return min; }
    public int maxSection() { return max; }
    public boolean contains(int section) { return initialized && section >= min && section < max; }
}
