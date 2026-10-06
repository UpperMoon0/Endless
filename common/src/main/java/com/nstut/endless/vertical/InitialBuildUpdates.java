package com.nstut.endless.vertical;

import java.util.HashSet;
import java.util.Set;

/** Retain dirty notifications rejected by native managers during a first build. */
public final class InitialBuildUpdates {
    public interface Target {
        boolean endless$isCurrentInitialSection(int x, int y, int z);
        boolean endless$rebuildInitialSection(int x, int y, int z);
    }
    private record Section(int x, int y, int z) {}
    private final Set<Section> sections = new HashSet<>();
    private final Target target;

    public InitialBuildUpdates(Target target) { this.target = target; }
    public void add(int x, int y, int z) {
        if (target.endless$isCurrentInitialSection(x, y, z)) sections.add(new Section(x, y, z));
    }
    public boolean contains(int x, int y, int z) { return sections.contains(new Section(x, y, z)); }
    public void discardColumn(int x, int z) { sections.removeIf(section -> section.x == x && section.z == z); }
    public void retry() {
        var iterator = sections.iterator();
        while (iterator.hasNext()) {
            var section = iterator.next();
            if (!target.endless$isCurrentInitialSection(section.x, section.y, section.z)
                || target.endless$rebuildInitialSection(section.x, section.y, section.z)) iterator.remove();
        }
    }
}
